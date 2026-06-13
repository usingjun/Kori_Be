package core.global.entity.image.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnregisteredPostImageCleanupSchedulerTest {

    @Mock
    private ImageUploadSessionService uploadSessionService;

    @InjectMocks
    private UnregisteredPostImageCleanupScheduler scheduler;

    @Test
    void scheduleCleanup_schedulesExpiredUploadSessions() {
        when(uploadSessionService.scheduleExpiredSessions()).thenReturn(2);

        scheduler.scheduleCleanup();

        verify(uploadSessionService).scheduleExpiredSessions();
    }

    @Test
    void retryFailedDeletes_reschedulesFailedUploadSessionDeletes() {
        when(uploadSessionService.retryFailedDeletes()).thenReturn(2);

        scheduler.retryFailedDeletes();

        verify(uploadSessionService).retryFailedDeletes();
    }

}
