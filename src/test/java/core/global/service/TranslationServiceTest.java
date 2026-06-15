package core.global.service;

import core.domain.user.entity.User;
import core.domain.user.entity.UserTestBuilder;
import core.domain.user.repository.UserRepository;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.translation.client.TranslationClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranslationServiceTest {

    @Mock UserRepository userRepository;
    @Mock TranslationClient translationClient;
    @Mock Authentication authentication;
    @InjectMocks TranslationService translationService;

    @Test
    void translateMessages_delegatesToClient() {
        List<String> messages = List.of("안녕하세요", "반갑습니다");
        when(translationClient.translate(messages, "en")).thenReturn(List.of("Hello", "Nice to meet you"));

        List<String> result = translationService.translateMessages(messages, "en");

        assertThat(result).containsExactly("Hello", "Nice to meet you");
    }

    @Test
    void translateMessages_returnsNullMessagesWithoutCallingClient() {
        assertThat(translationService.translateMessages(null, "en")).isNull();

        verify(translationClient, never()).translate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void translateMessages_returnsEmptyMessagesWithoutCallingClient() {
        List<String> messages = List.of();

        assertThat(translationService.translateMessages(messages, "en")).isSameAs(messages);
        verify(translationClient, never()).translate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void translateMessages_returnsOriginalWhenTargetLanguageIsBlank() {
        List<String> messages = List.of("hello");

        assertThat(translationService.translateMessages(messages, "")).isSameAs(messages);
        verify(translationClient, never()).translate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void translatePost_returnsSingleTranslatedText() {
        when(translationClient.translate(List.of("hello"), "ko")).thenReturn(List.of("안녕하세요"));

        assertThat(translationService.translatePost("hello", "ko")).isEqualTo("안녕하세요");
    }

    @Test
    void translateComments_keepsListContract() {
        List<String> comments = List.of("hello");
        when(translationClient.translate(comments, "ko")).thenReturn(List.of("안녕하세요"));

        assertThat(translationService.translateComments(comments, "ko")).containsExactly("안녕하세요");
    }

    @Test
    void fallbacks_returnOriginalInput() {
        List<String> messages = List.of("hello");
        RuntimeException failure = new RuntimeException("api down");

        assertThat(translationService.fallbackTranslateMessages(messages, "ko", failure)).isSameAs(messages);
        assertThat(translationService.fallbackTranslateSingle("hello", "ko", failure)).isEqualTo("hello");
    }

    @Test
    void saveUserLanguage_updatesExistingUser() {
        User user = UserTestBuilder.builder().id(1L).build();
        when(authentication.getName()).thenReturn("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        translationService.saveUserLanguage(authentication, "ko");

        assertThat(user.getTranslateLanguage()).isEqualTo("ko");
        verify(userRepository).save(user);
    }

    @Test
    void saveUserLanguage_throwsWhenUserDoesNotExist() {
        when(authentication.getName()).thenReturn("missing@example.com");
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> translationService.saveUserLanguage(authentication, "ko"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getError()).isEqualTo(UserErrorCode.USER_NOT_FOUND));
    }
}
