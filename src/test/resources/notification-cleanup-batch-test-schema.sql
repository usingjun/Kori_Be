DROP SCHEMA IF EXISTS notification_batch_test CASCADE;
CREATE SCHEMA notification_batch_test;

CREATE TABLE notification_batch_test.notification (
    notification_id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
