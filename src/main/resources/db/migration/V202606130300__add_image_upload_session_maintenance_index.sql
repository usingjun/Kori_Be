CREATE INDEX idx_image_upload_session_status_updated
    ON image_upload_session (status, updated_at);
