package core.global.entity.image.rabbitmq;

import core.global.entity.image.entity.ImageOperationPublishOutbox;
import core.global.entity.image.service.ImageOperationOutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageOperationOutboxRelayTest {

    @Mock
    private ImageOperationOutboxService outboxService;
    @Mock
    private ImageOperationRabbitPublisher publisher;

    @InjectMocks
    private ImageOperationOutboxRelay relay;

    @Test
    void publishPending_marksPublishedOnlyAfterConfirmedPublish() {
        ImageOperationPublishOutbox outbox = mock(ImageOperationPublishOutbox.class);
        UUID outboxId = UUID.randomUUID();
        when(outbox.getOutboxId()).thenReturn(outboxId);
        when(outboxService.findPendingBatch()).thenReturn(List.of(outbox));

        relay.publishPending();

        verify(publisher).publishWithConfirm(outbox);
        verify(outboxService).markPublished(outboxId);
        verify(outboxService, never()).recordFailure(any(), any());
    }

    @Test
    void publishPending_keepsPendingWhenConfirmedPublishFails() {
        ImageOperationPublishOutbox outbox = mock(ImageOperationPublishOutbox.class);
        UUID outboxId = UUID.randomUUID();
        when(outbox.getOutboxId()).thenReturn(outboxId);
        when(outboxService.findPendingBatch()).thenReturn(List.of(outbox));
        doThrow(new IllegalStateException("confirm failed")).when(publisher).publishWithConfirm(outbox);

        relay.publishPending();

        verify(outboxService, never()).markPublished(any());
        verify(outboxService).recordFailure(outboxId, "confirm failed");
    }
}
