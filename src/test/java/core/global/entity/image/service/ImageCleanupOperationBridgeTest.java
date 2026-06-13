package core.global.entity.image.service;

import core.global.enums.common.ImageCleanupOperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageCleanupOperationBridgeTest {

    @Mock
    private ImageOperationRecoveryService recoveryService;

    @InjectMocks
    private ImageCleanupOperationBridge bridge;

    @Test
    void recordCommonOutbox_schedulesCommonCleanupOperation() {
        bridge.recordCommonOutbox(10L, ImageCleanupOperationType.DELETE_FOLDER, "posts/1/");

        verify(recoveryService).scheduleFailedCleanup(
                10L,
                ImageCleanupOperationType.DELETE_FOLDER,
                "posts/1/"
        );
    }

    @Test
    void recordCommonOutbox_keepsFallbackActiveWhenCommonOutboxFails() {
        doThrow(new IllegalStateException("db down"))
                .when(recoveryService)
                .scheduleFailedCleanup(10L, ImageCleanupOperationType.DELETE_OBJECT, "posts/1/a.jpg");

        bridge.recordCommonOutbox(10L, ImageCleanupOperationType.DELETE_OBJECT, "posts/1/a.jpg");

        verify(recoveryService).scheduleFailedCleanup(
                10L,
                ImageCleanupOperationType.DELETE_OBJECT,
                "posts/1/a.jpg"
        );
    }
}
