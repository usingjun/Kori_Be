package core.global.entity.image.service;

import core.global.entity.image.entity.ImageOperationPublishOutbox;
import core.global.entity.image.repository.ImageOperationPublishOutboxRepository;
import core.global.enums.common.ImageOperationOutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageOperationOutboxService {

    private final ImageOperationPublishOutboxRepository outboxRepository;

    @Transactional(readOnly = true)
    public List<ImageOperationPublishOutbox> findPendingBatch() {
        return outboxRepository.findTop50ByStatusOrderByCreatedAtAsc(ImageOperationOutboxStatus.PENDING);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID outboxId) {
        outboxRepository.findById(outboxId).orElseThrow().markPublished();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID outboxId, String errorMessage) {
        outboxRepository.findById(outboxId).orElseThrow().recordFailure(errorMessage);
    }
}
