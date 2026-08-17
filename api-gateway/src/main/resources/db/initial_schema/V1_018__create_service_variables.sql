CREATE TABLE service_variables (
    id              UUID            PRIMARY KEY,
    service_id      UUID            NOT NULL REFERENCES services(id),
    created_by      UUID            REFERENCES users(id),
    key             VARCHAR(193)    NOT NULL,
    value           TEXT            NOT NULL,
    secret          BOOLEAN         NOT NULL,
    encrypted       BOOLEAN         NOT NULL DEFAULT true,
    value_iv        VARCHAR(64),
    deleted         BOOLEAN         NOT NULL DEFAULT false,
    deleted_at      TIMESTAMP(0),
    purge_after     TIMESTAMP(0),
    created_at      TIMESTAMP(0)    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP(0)    NOT NULL DEFAULT now(),
    system_type     VARCHAR(8)      CHECK (system_type IN ('config', 'storage', 'override')),
    UNIQUE(service_id, key),
    CHECK (secret = false OR encrypted = true)
);
