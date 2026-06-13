CREATE TABLE image_operation (
    operation_id UUID PRIMARY KEY,
    operation_type VARCHAR(50) NOT NULL,
    owner_type VARCHAR(30) NOT NULL,
    owner_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6)
);

CREATE INDEX idx_image_operation_owner
    ON image_operation (owner_type, owner_id, created_at);

CREATE INDEX idx_image_operation_status
    ON image_operation (status, updated_at);

CREATE TABLE image_operation_step (
    step_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL,
    step_type VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    source_key VARCHAR(500),
    target_key VARCHAR(500),
    source_etag VARCHAR(255),
    source_content_length BIGINT,
    result_etag VARCHAR(255),
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    last_error TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    CONSTRAINT fk_image_operation_step_operation
        FOREIGN KEY (operation_id) REFERENCES image_operation (operation_id),
    CONSTRAINT uk_image_operation_step_target
        UNIQUE (operation_id, step_type, target_key)
);

CREATE INDEX idx_image_operation_step_operation
    ON image_operation_step (operation_id, created_at);

CREATE INDEX idx_image_operation_step_status
    ON image_operation_step (status, updated_at);
