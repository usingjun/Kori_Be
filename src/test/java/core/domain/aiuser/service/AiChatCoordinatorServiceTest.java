package core.domain.aiuser.service;

import core.domain.aiuser.dto.MessageCreatedEvent;
import core.domain.chat.dto.ChatMessageResponse;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.User;
import core.global.enums.chat.MessageType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatCoordinatorServiceTest {

    @Test
    void fastResponseKeepsThinkingStateUntilAsyncAiCallCompletes() {
        AiChatUserService aiChatUserService = mock(AiChatUserService.class);
        ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
        AiThinkingStateManager thinkingStateManager = new AiThinkingStateManager();
        AiChatCoordinatorService coordinator = new AiChatCoordinatorService(
                aiChatUserService,
                mock(ChatMessageRepository.class),
                mock(ChatRoomRepository.class),
                taskScheduler,
                thinkingStateManager
        );

        User aiUser = mock(User.class);
        when(aiUser.getId()).thenReturn(7L);
        Sinks.One<Boolean> result = Sinks.one();
        MessageCreatedEvent event = new MessageCreatedEvent(
                new ChatMessageResponse(
                        1L, 10L, 20L, "hello", "hello", Instant.now(),
                        "user", "name", null, MessageType.TEXT, null, null
                ),
                List.of()
        );
        when(aiChatUserService.processAiResponse(aiUser, event, "hello", true))
                .thenReturn(result.asMono());

        ReflectionTestUtils.invokeMethod(
                coordinator,
                "scheduleFastResponse",
                aiUser,
                event,
                "hello",
                true,
                0L
        );

        assertThat(thinkingStateManager.isThinking(10L, 7L)).isTrue();

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(taskCaptor.capture(), any(Instant.class));
        taskCaptor.getValue().run();

        assertThat(thinkingStateManager.isThinking(10L, 7L)).isTrue();

        result.tryEmitValue(true);

        assertThat(thinkingStateManager.isThinking(10L, 7L)).isFalse();
    }
}
