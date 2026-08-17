-- 'skipped' joins the probe status vocabulary: the scheduler records shed
-- ticks (dispatch backlog / queue full) as probe_results rows.
ALTER TABLE probe_results DROP CONSTRAINT probe_results_status_check;
ALTER TABLE probe_results ADD CONSTRAINT probe_results_status_check
    CHECK (status IN ('success', 'failure', 'timeout', 'error', 'skipped'));
