CREATE TABLE notification_log (
    id              UUID            PRIMARY KEY,
    organization_id UUID            NOT NULL REFERENCES organizations(id),
    service_id      UUID            REFERENCES services(id),
    probe_result_id UUID            REFERENCES probe_results(id),
    channel         VARCHAR(8)      NOT NULL CHECK (channel IN ('email', 'webhook')),
    recipient       VARCHAR(255)    NOT NULL,
    status          VARCHAR(16)     NOT NULL CHECK (status IN ('queued', 'sent', 'failed', 'suppressed')),
    attempt_count   SMALLINT        NOT NULL DEFAULT 1,
    error           TEXT,
    created_at      TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_log_org ON notification_log(organization_id, created_at DESC);
