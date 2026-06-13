CREATE TABLE failed_image_cleanup (
    id BIGSERIAL PRIMARY KEY,
    operation_type VARCHAR(30) NOT NULL,
    target_key VARCHAR(500) NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP(6) NOT NULL,
    last_error TEXT,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_failed_image_cleanup_operation_target UNIQUE (operation_type, target_key)
);

CREATE INDEX idx_failed_image_cleanup_retry
    ON failed_image_cleanup (status, next_retry_at);
