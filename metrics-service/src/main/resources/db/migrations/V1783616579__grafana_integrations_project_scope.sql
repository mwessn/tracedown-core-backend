-- Grafana integrations become per-project (one active integration per
-- project). Existing rows carry no project and cannot be mapped — the
-- feature had no UI yet, so they are dropped rather than migrated.
DELETE FROM grafana_integrations;

ALTER TABLE grafana_integrations
    ADD COLUMN project_id UUID NOT NULL REFERENCES projects(id);

CREATE UNIQUE INDEX idx_grafana_integrations_project
    ON grafana_integrations(project_id) WHERE deleted = false;
