CREATE TABLE image_operation_payload (
    operation_id UUID PRIMARY KEY,
    image_type VARCHAR(50) NOT NULL,
    final_url VARCHAR(500) NOT NULL,
    order_index INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_image_operation_payload_operation
        FOREIGN KEY (operation_id) REFERENCES image_operation (operation_id)
);
