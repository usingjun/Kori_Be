package core.global.entity.image.rabbitmq;

public final class ImageOperationRabbitNames {

    public static final String STEP_QUEUE = "image.operation.step.queue";
    public static final String STEP_RETRY_1M_QUEUE = "image.operation.step.retry.1m.queue";
    public static final String STEP_RETRY_5M_QUEUE = "image.operation.step.retry.5m.queue";
    public static final String STEP_RETRY_15M_QUEUE = "image.operation.step.retry.15m.queue";
    public static final String STEP_RETRY_1H_QUEUE = "image.operation.step.retry.1h.queue";
    public static final String STEP_DLQ = "image.operation.step.dlq";

    public static final String COMPENSATE_ROUTING_KEY = "image.operation.step.compensate";
    public static final String COPY_ROUTING_KEY = "image.operation.step.copy";
    public static final String REGISTER_IMAGE_DB_ROUTING_KEY = "image.operation.step.register-image-db";
    public static final String DELETE_STAGING_ROUTING_KEY = "image.operation.step.delete-staging";
    public static final String DELETE_OBJECT_ROUTING_KEY = "image.operation.step.delete-object";
    public static final String DELETE_FOLDER_ROUTING_KEY = "image.operation.step.delete-folder";
    public static final String STEP_RETRY_PREFIX = "image.operation.step.retry.";
    public static final String STEP_DLQ_ROUTING_KEY = "image.operation.step.dlq";

    private ImageOperationRabbitNames() {
    }
}
