-- Skipped probes (dispatch queue full) are recorded as probe_results rows
-- with status 'skipped' — they never reached an agent, so the agent
-- reference must allow NULL.
ALTER TABLE probe_results ALTER COLUMN probe_agent_id DROP NOT NULL;
