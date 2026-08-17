-- Skipped rows carry no agent — they must go before NOT NULL returns.
DELETE FROM probe_results WHERE probe_agent_id IS NULL;

ALTER TABLE probe_results ALTER COLUMN probe_agent_id SET NOT NULL;
