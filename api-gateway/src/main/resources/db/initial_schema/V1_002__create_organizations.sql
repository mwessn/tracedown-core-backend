CREATE TABLE organizations (
    id              UUID            PRIMARY KEY,
    name            VARCHAR(128)    NOT NULL,
    owner_id        UUID            NOT NULL REFERENCES users(id),
    totp_required   BOOLEAN         NOT NULL DEFAULT false,
    deleted         BOOLEAN         NOT NULL DEFAULT false,
    deleted_at      TIMESTAMP(0),
    purge_after     TIMESTAMP(0),
    created_at      TIMESTAMP(0)    NOT NULL DEFAULT now()
);
