CREATE TABLE org_domains (
    id                  UUID            PRIMARY KEY,
    organization_id     UUID            NOT NULL REFERENCES organizations(id),
    domain              VARCHAR(256)    NOT NULL,
    challenge           TEXT            NOT NULL,
    verification_type   VARCHAR(16)     NOT NULL,
    status              VARCHAR(16)     NOT NULL,
    verified_at         TIMESTAMP(0),
    exceptions          JSONB,
    wildcard_enabled    BOOLEAN         NOT NULL DEFAULT true,
    last_checked_at     TIMESTAMP,
    lapsed              BOOLEAN         NOT NULL DEFAULT false,
    deleted             BOOLEAN         NOT NULL DEFAULT false,
    deleted_at          TIMESTAMP(0),
    purge_after         TIMESTAMP(0)
);
