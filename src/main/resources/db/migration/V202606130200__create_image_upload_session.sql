CREATE TABLE image_upload_session (
    upload_id UUID PRIMARY KEY,
    object_key VARCHAR(500) NOT NULL UNIQUE,
    owner_id BIGINT NOT NULL,
    image_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    registered_at TIMESTAMP(6),
    deleted_at TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_image_upload_session_expiry
    ON image_upload_session (status, expires_at);
