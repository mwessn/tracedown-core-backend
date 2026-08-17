-- Restore the plain (NO ACTION) foreign key.
ALTER TABLE services DROP CONSTRAINT fk_services_last_run_id;
ALTER TABLE services ADD CONSTRAINT fk_services_last_run_id
    FOREIGN KEY (last_run_id) REFERENCES probe_results(id);
