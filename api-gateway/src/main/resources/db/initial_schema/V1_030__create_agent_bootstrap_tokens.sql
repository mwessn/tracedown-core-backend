CREATE TABLE agent_bootstrap_tokens (
    id                      UUID            PRIMARY KEY,
    slug                    VARCHAR(64)     NOT NULL,
    label                   VARCHAR(64)     NOT NULL,
    token_hash              VARCHAR(255)    NOT NULL,
    expires_at              TIMESTAMP(0)    NOT NULL,
    used                    BOOLEAN         NOT NULL DEFAULT false,
    used_at                 TIMESTAMP(0),
    created_by              UUID            REFERENCES users(id),
    created_at              TIMESTAMP(0)    NOT NULL DEFAULT now()
);
