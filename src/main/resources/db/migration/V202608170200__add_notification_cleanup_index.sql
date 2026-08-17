-- flyway:executeInTransaction=false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_notification_cleanup_created_at_id
    ON notification (created_at, notification_id);
