CREATE TABLE probe_results (
    id              UUID            PRIMARY KEY,
    service_id      UUID            NOT NULL REFERENCES services(id),
    probe_agent_id  BIGINT          NOT NULL REFERENCES probe_agents(id),
    started_at      TIMESTAMP(0)    NOT NULL DEFAULT now(),
    status          VARCHAR(8)      NOT NULL CHECK (status IN ('success', 'failure', 'timeout', 'error')),
    run_duration_ms INTEGER         NOT NULL,
    total_response_ms INTEGER       NOT NULL DEFAULT 0,
    raw_result      JSONB           NOT NULL,
    project_id      UUID            NOT NULL REFERENCES projects(id),
    workspace_id    UUID            NOT NULL REFERENCES workspaces(id),
    organization_id UUID            NOT NULL REFERENCES organizations(id)
);

CREATE INDEX idx_probe_results_service ON probe_results(service_id, started_at DESC);
CREATE INDEX idx_probe_results_org ON probe_results(organization_id, started_at DESC);
