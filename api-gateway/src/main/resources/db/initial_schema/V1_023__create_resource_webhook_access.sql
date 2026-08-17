CREATE TABLE resource_webhook_access (
    id                  UUID            PRIMARY KEY,
    org_id              UUID            NOT NULL REFERENCES organizations(id),
    resource_type       VARCHAR(16)     NOT NULL CHECK (resource_type IN ('workspace', 'project', 'service')),
    resource_id         UUID            NOT NULL,
    webhook_delivery_id UUID            NOT NULL REFERENCES webhook_deliveries(id),
    enabled             BOOLEAN         NOT NULL DEFAULT true,
    created_at          TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE INDEX idx_resource_webhook_resource ON resource_webhook_access(resource_type, resource_id);
CREATE INDEX idx_resource_webhook_delivery ON resource_webhook_access(webhook_delivery_id);
CREATE INDEX idx_resource_webhook_org ON resource_webhook_access(org_id);
