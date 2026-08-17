-- The notification log is a historical record of what was sent, when and to
-- whom. The service and probe result it mentions age out on their own
-- schedules (retention, purge of soft-deleted services); the log entry must
-- neither block those deletions nor be destroyed by them. Its own lifetime is
-- governed by notification-log retention and by organization purge.
ALTER TABLE notification_log DROP CONSTRAINT notification_log_service_id_fkey;
ALTER TABLE notification_log ADD CONSTRAINT notification_log_service_id_fkey
    FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE SET NULL;

ALTER TABLE notification_log DROP CONSTRAINT notification_log_probe_result_id_fkey;
ALTER TABLE notification_log ADD CONSTRAINT notification_log_probe_result_id_fkey
    FOREIGN KEY (probe_result_id) REFERENCES probe_results(id) ON DELETE SET NULL;
