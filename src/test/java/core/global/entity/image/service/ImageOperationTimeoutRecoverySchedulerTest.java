package core.global.entity.image.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ImageOperationTimeoutRecoverySchedulerTest {

    @Mock
    private ImageOperationRecoveryService recoveryService;

    @InjectMocks
    private ImageOperationTimeoutRecoveryScheduler scheduler;

    @Test
    void recoverTimedOutCompensations_usesConfiguredTimeout() {
        ReflectionTestUtils.setField(scheduler, "processingTimeout", Duration.ofMinutes(5));
        LocalDateTime expected = LocalDateTime.now().minusMinutes(5);

        scheduler.recoverTimedOutCompensations();

        verify(recoveryService).recoverTimedOutCompensations(argThat(actual ->
                !actual.isBefore(expected.minusSeconds(1))
                        && !actual.isAfter(expected.plusSeconds(1))
        ));
    }
}
