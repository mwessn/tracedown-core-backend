CREATE TABLE grafana_integrations (
    id              UUID            PRIMARY KEY,
    organization_id UUID            NOT NULL REFERENCES organizations(id),
    name            VARCHAR(64)     NOT NULL,
    config          JSONB           NOT NULL,
    enabled         BOOLEAN         NOT NULL DEFAULT false,
    deleted         BOOLEAN         NOT NULL DEFAULT false,
    deleted_at      TIMESTAMP(0),
    purge_after     TIMESTAMP(0),
    created_at      TIMESTAMP(0)    NOT NULL DEFAULT now()
);

CREATE INDEX idx_grafana_integrations_org ON grafana_integrations(organization_id) WHERE deleted = false;
