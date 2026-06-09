package core.global.entity.image.rabbitmq;

import core.global.entity.image.service.ImageCompensationExecutor;
import core.global.entity.image.service.ImageOperationRecoveryService;
import core.global.enums.common.ImageOperationStepType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "image.cleanup.rabbit.enabled", havingValue = "true")
public class ImageOperationRabbitConsumer {

    private final ImageOperationRecoveryService recoveryService;
    private final ImageCompensationExecutor compensationExecutor;

    @RabbitListener(queues = ImageOperationRabbitNames.STEP_QUEUE)
    public void consume(ImageOperationMessage message) {
        ImageOperationRecoveryService.ImageOperationMessageView view = view(message);
        if (!recoveryService.begin(view)) {
            return;
        }

        try {
            if (message.stepType() != ImageOperationStepType.COMPENSATE_FINAL_OBJECT) {
                throw new IllegalArgumentException("Unsupported image operation step type " + message.stepType());
            }
            compensationExecutor.deleteFinalObject(message.targetKey());
            recoveryService.markCompleted(view);
        } catch (Exception e) {
            ImageOperationRecoveryService.FailureDecision decision =
                    recoveryService.markFailedAndSchedule(view, e.getMessage());
            log.warn("[ImageOperationConsumer] compensation failed operationId={} stepId={} attempt={} exhausted={}",
                    message.operationId(), message.stepId(), decision.attempt(), decision.exhausted(), e);
        }
    }

    private ImageOperationRecoveryService.ImageOperationMessageView view(ImageOperationMessage message) {
        return new ImageOperationRecoveryService.ImageOperationMessageView(
                message.messageId(),
                message.operationId(),
                message.stepId(),
                message.stepType(),
                message.targetKey(),
                message.attempt()
        );
    }
}
