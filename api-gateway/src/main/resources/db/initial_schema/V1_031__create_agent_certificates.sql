CREATE TABLE agent_certificates (
    id                      UUID            PRIMARY KEY,
    probe_agent_id          BIGINT          NOT NULL REFERENCES probe_agents(id),
    certificate_pem         TEXT            NOT NULL,
    fingerprint             VARCHAR(128)    NOT NULL UNIQUE,
    issued_at               TIMESTAMP(0)    NOT NULL,
    expires_at              TIMESTAMP(0)    NOT NULL,
    revoked                 BOOLEAN         NOT NULL DEFAULT false,
    revoked_at              TIMESTAMP(0),
    revoked_reason          TEXT,
    created_at              TIMESTAMP(0)    NOT NULL DEFAULT now()
);
