-- Skipped rows must go before the narrower constraint returns.
DELETE FROM probe_results WHERE status = 'skipped';

ALTER TABLE probe_results DROP CONSTRAINT probe_results_status_check;
ALTER TABLE probe_results ADD CONSTRAINT probe_results_status_check
    CHECK (status IN ('success', 'failure', 'timeout', 'error'));
