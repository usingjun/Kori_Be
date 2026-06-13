CREATE TABLE image_operation_publish_outbox (
    outbox_id UUID PRIMARY KEY,
    message_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    step_id UUID NOT NULL,
    step_type VARCHAR(50) NOT NULL,
    target_key VARCHAR(500) NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    destination VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    last_error TEXT,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6),
    CONSTRAINT uk_image_operation_publish_outbox_message UNIQUE (message_id),
    CONSTRAINT fk_image_operation_publish_outbox_operation
        FOREIGN KEY (operation_id) REFERENCES image_operation (operation_id),
    CONSTRAINT fk_image_operation_publish_outbox_step
        FOREIGN KEY (step_id) REFERENCES image_operation_step (step_id)
);

CREATE INDEX idx_image_operation_publish_outbox_status
    ON image_operation_publish_outbox (status, created_at);

CREATE TABLE image_operation_consumed_message (
    message_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL,
    step_id UUID NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    consumed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_image_operation_consumed_message_operation
        FOREIGN KEY (operation_id) REFERENCES image_operation (operation_id),
    CONSTRAINT fk_image_operation_consumed_message_step
        FOREIGN KEY (step_id) REFERENCES image_operation_step (step_id)
);

CREATE INDEX idx_image_operation_consumed_message_operation
    ON image_operation_consumed_message (operation_id, step_id);
