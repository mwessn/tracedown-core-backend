-- Agents are referenced by probe_results (non-nullable FK), so removal is a
-- decommission flag rather than a row delete: history stays intact, the
-- agent disappears from lists, selection and health checks.
ALTER TABLE probe_agents ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT false;
