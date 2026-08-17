CREATE TABLE webhook_deliveries (
    id              UUID            PRIMARY KEY,
    organization_id UUID            NOT NULL REFERENCES organizations(id),
    name            VARCHAR(64)     NOT NULL,
    label           VARCHAR(64),
    url             VARCHAR(512)    NOT NULL,
    method          VARCHAR(8)      NOT NULL DEFAULT 'POST'
                    CHECK (method IN ('GET', 'POST', 'PUT', 'PATCH')),
    body            JSONB,
    config          JSONB,
    attempt_count   SMALLINT        NOT NULL DEFAULT 1,
    deleted         BOOLEAN         NOT NULL DEFAULT false,
    deleted_at      TIMESTAMP(0),
    purge_after     TIMESTAMP(0),
    created_at      TIMESTAMP(0)    NOT NULL DEFAULT now()
);
