CREATE TABLE resource_permissions (
    id              UUID            PRIMARY KEY,
    org_id          UUID            NOT NULL REFERENCES organizations(id),
    principal_type  VARCHAR(16)     NOT NULL CHECK (principal_type IN ('org_user', 'org_group')),
    principal_id    UUID            NOT NULL,
    resource_type   VARCHAR(16)     NOT NULL CHECK (resource_type IN ('workspace', 'project', 'service')),
    resource_id     UUID            NOT NULL,
    permissions     SMALLINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_resource_permissions_principal ON resource_permissions(principal_type, principal_id);
CREATE INDEX idx_resource_permissions_resource ON resource_permissions(resource_type, resource_id);
CREATE INDEX idx_resource_permissions_org ON resource_permissions(org_id);
