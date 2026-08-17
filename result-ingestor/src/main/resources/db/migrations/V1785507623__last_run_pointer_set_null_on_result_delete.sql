-- services.last_run_id is a convenience pointer to the most recent probe
-- result, not owned data. A plain FK here means deleting old probe results
-- (data retention, or purging a soft-deleted service's history leaf-first)
-- fails whenever a service still points at one of the rows being removed —
-- e.g. a service that has been disabled longer than the retention window.
-- Clearing the pointer on result deletion is always safe: the denormalized
-- last_status columns on services keep the displayed state.
ALTER TABLE services DROP CONSTRAINT fk_services_last_run_id;
ALTER TABLE services ADD CONSTRAINT fk_services_last_run_id
    FOREIGN KEY (last_run_id) REFERENCES probe_results(id) ON DELETE SET NULL;
