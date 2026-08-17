CREATE TABLE org_groups (
    id              UUID            PRIMARY KEY,
    organization_id UUID            NOT NULL REFERENCES organizations(id),
    name            VARCHAR(64)     NOT NULL,
    totp_required   BOOLEAN         NOT NULL DEFAULT false,
    org_user_list   SMALLINT        NOT NULL DEFAULT 0,
    org_settings    SMALLINT        NOT NULL DEFAULT 0,
    org_domains     SMALLINT        NOT NULL DEFAULT 0,
    org_webhooks    SMALLINT        NOT NULL DEFAULT 0,
    org_workspaces  SMALLINT        NOT NULL DEFAULT 0
);
