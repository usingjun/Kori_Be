package core.global.entity.image.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageUploadSessionMaintenanceSchedulerTest {

    @Mock
    private ImageUploadSessionService uploadSessionService;

    @InjectMocks
    private ImageUploadSessionMaintenanceScheduler scheduler;

    @Test
    void purgeTerminalSessions_removesOldTerminalSessions() {
        when(uploadSessionService.purgeTerminalSessions()).thenReturn(3L);

        scheduler.purgeTerminalSessions();

        verify(uploadSessionService).purgeTerminalSessions();
    }
}
