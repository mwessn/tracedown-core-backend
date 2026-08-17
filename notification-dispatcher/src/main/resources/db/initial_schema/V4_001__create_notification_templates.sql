CREATE TABLE notification_templates (
    id              UUID            PRIMARY KEY,
    organization_id UUID            NOT NULL REFERENCES organizations(id),
    name            VARCHAR(64)     NOT NULL,
    text            TEXT            NOT NULL,
    deleted         BOOLEAN         NOT NULL DEFAULT false,
    deleted_at      TIMESTAMP(0),
    purge_after     TIMESTAMP(0),
    created_at      TIMESTAMP(0)    NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_notification_templates_name
    ON notification_templates(organization_id, name) WHERE deleted = false;
