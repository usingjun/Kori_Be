CREATE TABLE image_cleanup_operation (
    operation_id UUID PRIMARY KEY,
    operation_type VARCHAR(30) NOT NULL,
    target_key VARCHAR(500) NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    last_error TEXT,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    CONSTRAINT uk_image_cleanup_operation_target UNIQUE (operation_type, target_key)
);

CREATE INDEX idx_image_cleanup_operation_status
    ON image_cleanup_operation (status, updated_at);

CREATE TABLE image_cleanup_audit_log (
    audit_id BIGSERIAL PRIMARY KEY,
    operation_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30),
    message TEXT,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_image_cleanup_audit_operation
        FOREIGN KEY (operation_id) REFERENCES image_cleanup_operation (operation_id)
);

CREATE INDEX idx_image_cleanup_audit_operation
    ON image_cleanup_audit_log (operation_id, created_at);

CREATE TABLE image_cleanup_consumed_message (
    message_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    consumed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_image_cleanup_consumed_operation
        FOREIGN KEY (operation_id) REFERENCES image_cleanup_operation (operation_id)
);

CREATE INDEX idx_image_cleanup_consumed_operation
    ON image_cleanup_consumed_message (operation_id);
