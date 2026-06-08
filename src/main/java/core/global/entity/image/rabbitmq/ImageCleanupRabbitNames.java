package core.global.entity.image.rabbitmq;

public final class ImageCleanupRabbitNames {

    public static final String EXCHANGE = "image.operation.exchange";
    public static final String QUEUE = "image.operation.queue";
    public static final String RETRY_EXCHANGE = "image.operation.retry.exchange";
    public static final String RETRY_1M_QUEUE = "image.operation.retry.1m.queue";
    public static final String RETRY_5M_QUEUE = "image.operation.retry.5m.queue";
    public static final String RETRY_15M_QUEUE = "image.operation.retry.15m.queue";
    public static final String RETRY_1H_QUEUE = "image.operation.retry.1h.queue";
    public static final String DLX = "image.operation.dlx";
    public static final String DLQ = "image.operation.dlq";

    public static final String DELETE_OBJECT_ROUTING_KEY = "image.cleanup.delete-object";
    public static final String DELETE_FOLDER_ROUTING_KEY = "image.cleanup.delete-folder";
    public static final String DLQ_ROUTING_KEY = "image.cleanup.dlq";

    private ImageCleanupRabbitNames() {
    }
}
