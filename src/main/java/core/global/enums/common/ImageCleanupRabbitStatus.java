package core.global.enums.common;

public enum ImageCleanupRabbitStatus {
    PENDING,
    PROCESSING,
    RETRY_WAITING,
    COMPLETED,
    DLQ
}
