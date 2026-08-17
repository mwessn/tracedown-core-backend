CREATE TABLE notification_silences (
    id              UUID            PRIMARY KEY,
    org_user_id     UUID            NOT NULL REFERENCES org_users(id),
    workspace_id    UUID            REFERENCES workspaces(id),
    project_id      UUID            REFERENCES projects(id),
    service_id      UUID            REFERENCES services(id),
    channel         VARCHAR(16)     NOT NULL,
    config          JSONB,
    quiet_hours     VARCHAR(256)
);

CREATE INDEX idx_notification_silences_org_user ON notification_silences(org_user_id);
