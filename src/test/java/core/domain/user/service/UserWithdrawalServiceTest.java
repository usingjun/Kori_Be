package core.domain.user.service;

import core.domain.user.dto.UserWithdrawalEvent;
import core.domain.user.entity.User;
import core.domain.user.entity.UserTestBuilder;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.withdrawal.UserWithdrawalCommand;
import core.domain.user.service.withdrawal.UserWithdrawalContext;
import core.global.apple.service.AppleWithdrawalService;
import core.global.enums.Oauthplatform;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalServiceTest {

    @Mock UserRepository userRepository;
    @Mock AppleWithdrawalService appleWithdrawalService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock UserWithdrawalCommand firstCommand;
    @Mock UserWithdrawalCommand secondCommand;
    @Mock UserWithdrawalCommand lastCommand;

    @Test
    void withdraw_throwsWhenUserDoesNotExist() {
        UserWithdrawalService service = service();
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(1L, "access-token"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getError()).isEqualTo(UserErrorCode.USER_NOT_FOUND));

        verify(firstCommand, never()).execute(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void withdraw_executesCommandsInOrderThenPublishesEvent() {
        User user = UserTestBuilder.builder().id(1L).build();
        user.updateProvider(Oauthplatform.APPLE.toString());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(firstCommand.order()).thenReturn(100);
        when(secondCommand.order()).thenReturn(200);
        when(lastCommand.order()).thenReturn(1000);

        boolean appleAccount = service().withdraw(1L, "access-token");

        assertThat(appleAccount).isTrue();
        verify(appleWithdrawalService).revokeAppleToken(user);

        InOrder executionOrder = inOrder(firstCommand, secondCommand, lastCommand, eventPublisher);
        executionOrder.verify(firstCommand).execute(new UserWithdrawalContext(user));
        executionOrder.verify(secondCommand).execute(new UserWithdrawalContext(user));
        executionOrder.verify(lastCommand).execute(new UserWithdrawalContext(user));
        executionOrder.verify(eventPublisher).publishEvent(any(UserWithdrawalEvent.class));

        ArgumentCaptor<UserWithdrawalEvent> eventCaptor = ArgumentCaptor.forClass(UserWithdrawalEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getUserId()).isEqualTo(1L);
        assertThat(eventCaptor.getValue().getAccessToken()).isEqualTo("access-token");
    }

    @Test
    void withdraw_doesNotRevokeNonAppleAccount() {
        User user = UserTestBuilder.builder().id(1L).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        boolean appleAccount = service().withdraw(1L, "access-token");

        assertThat(appleAccount).isFalse();
        verify(appleWithdrawalService, never()).revokeAppleToken(user);
    }

    @Test
    void withdraw_stopsWhenCommandFailsAndDoesNotPublishEvent() {
        User user = UserTestBuilder.builder().id(1L).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(firstCommand.order()).thenReturn(100);
        when(secondCommand.order()).thenReturn(200);
        when(lastCommand.order()).thenReturn(1000);
        org.mockito.Mockito.doThrow(new IllegalStateException("cleanup failed"))
                .when(secondCommand).execute(any());

        assertThatThrownBy(() -> service().withdraw(1L, "access-token"))
                .isInstanceOf(IllegalStateException.class);

        verify(lastCommand, never()).execute(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    private UserWithdrawalService service() {
        return new UserWithdrawalService(
                userRepository,
                appleWithdrawalService,
                eventPublisher,
                List.of(lastCommand, secondCommand, firstCommand)
        );
    }
}
