-- Restore the plain (NO ACTION) foreign keys. Columns are nullable, so rows
-- whose source rows were deleted under the forward migration remain valid.
ALTER TABLE notification_log DROP CONSTRAINT notification_log_service_id_fkey;
ALTER TABLE notification_log ADD CONSTRAINT notification_log_service_id_fkey
    FOREIGN KEY (service_id) REFERENCES services(id);

ALTER TABLE notification_log DROP CONSTRAINT notification_log_probe_result_id_fkey;
ALTER TABLE notification_log ADD CONSTRAINT notification_log_probe_result_id_fkey
    FOREIGN KEY (probe_result_id) REFERENCES probe_results(id);
