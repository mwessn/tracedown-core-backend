CREATE TABLE org_rule_presets (
    id              UUID            PRIMARY KEY,
    organization_id UUID            NOT NULL REFERENCES organizations(id),
    workspace_id    UUID            REFERENCES workspaces(id),
    created_by      UUID            NOT NULL REFERENCES users(id),
    display_name    VARCHAR(128)    NOT NULL,
    script          TEXT            NOT NULL,
    deleted         BOOLEAN         NOT NULL DEFAULT false,
    deleted_at      TIMESTAMP(0),
    purge_after     TIMESTAMP(0),
    created_at      TIMESTAMP       NOT NULL DEFAULT now()
);
