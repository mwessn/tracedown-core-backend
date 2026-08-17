DROP INDEX IF EXISTS idx_grafana_integrations_project;

ALTER TABLE grafana_integrations
    DROP COLUMN IF EXISTS project_id;
