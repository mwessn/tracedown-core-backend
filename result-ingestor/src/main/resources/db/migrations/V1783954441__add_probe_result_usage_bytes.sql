-- Measured HTTP-layer usage per probe run (agent-supplied): bytes the probe
-- put on the wire (egress) and took off it (ingress). Feeds the usage tabs.
ALTER TABLE probe_results ADD COLUMN ingress_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE probe_results ADD COLUMN egress_bytes  BIGINT NOT NULL DEFAULT 0;
-- Bytes the scheduler dispatched to the agent to run this probe (the request
-- body sent to the agent). A neutral per-run dispatch metric.
ALTER TABLE probe_results ADD COLUMN agent_egress_bytes BIGINT NOT NULL DEFAULT 0;
-- Number of HTTP calls this run made (chain length). A neutral per-run metric,
-- avoiding a JSONB parse of raw_result to count calls for usage aggregation.
ALTER TABLE probe_results ADD COLUMN request_count INTEGER NOT NULL DEFAULT 0;
