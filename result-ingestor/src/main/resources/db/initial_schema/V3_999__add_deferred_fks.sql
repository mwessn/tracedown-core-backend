-- Deferred FK: services.last_run_id → probe_results.id
-- Could not be added in V1_005 because probe_results (V3_001) didn't exist yet.
ALTER TABLE services
    ADD CONSTRAINT fk_services_last_run_id
    FOREIGN KEY (last_run_id) REFERENCES probe_results(id);
