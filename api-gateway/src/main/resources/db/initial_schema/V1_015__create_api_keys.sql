CREATE TABLE api_keys (
    id              UUID            PRIMARY KEY,
    organization_id UUID            NOT NULL REFERENCES organizations(id),
    created_by      UUID            NOT NULL REFERENCES users(id),
    name            VARCHAR(128)    NOT NULL,
    key_hash        VARCHAR(255)    NOT NULL,
    last_used_at    TIMESTAMP,
    expires_at      TIMESTAMP,
    revoked         BOOLEAN         NOT NULL DEFAULT false,
    deleted         BOOLEAN         NOT NULL DEFAULT false,
    deleted_at      TIMESTAMP(0),
    purge_after     TIMESTAMP(0),
    created_at      TIMESTAMP       NOT NULL DEFAULT now()
);
