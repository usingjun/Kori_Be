package core.global.enums.common;

public enum ImageOperationStatus {
    PENDING,
    PROCESSING,
    RETRY_WAITING,
    COMPLETED,
    COMPENSATED,
    FAILED,
    DLQ
}
