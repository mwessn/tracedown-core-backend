CREATE TABLE org_audit_log (
    id              UUID            PRIMARY KEY,
    organization_id UUID            NOT NULL REFERENCES organizations(id),
    user_id         UUID            REFERENCES users(id),
    action          VARCHAR(64)     NOT NULL,
    entity_type     VARCHAR(64),
    entity_id       VARCHAR(64),
    -- What the entity was called at the moment of the change, captured inline so
    -- the log reads without a join and survives the entity's later rename/deletion.
    -- Null for system-wide actions that target no single named entity.
    entity_display_name VARCHAR(256),
    diff            JSONB,
    comment         TEXT,
    created_at      TIMESTAMP(0)    NOT NULL DEFAULT now()
);

CREATE INDEX idx_org_audit_log_org ON org_audit_log(organization_id);
CREATE INDEX idx_org_audit_log_created ON org_audit_log(created_at);
