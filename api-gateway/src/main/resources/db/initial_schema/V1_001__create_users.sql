CREATE TABLE users (
    id              UUID            PRIMARY KEY,
    email           VARCHAR(256)    NOT NULL UNIQUE,
    password_hash   VARCHAR(256)    NOT NULL,
    display_name    VARCHAR(128)    NOT NULL,
    totp_secret_encrypted VARCHAR(512),
    totp_secret_iv  VARCHAR(32),
    totp_enrolled_at TIMESTAMP(0),
    totp_last_used_at TIMESTAMP(0),
    totp_enabled    BOOLEAN         NOT NULL DEFAULT false,
    selected_org_id UUID,
    is_active       BOOLEAN         NOT NULL DEFAULT true,
    deleted         BOOLEAN         NOT NULL DEFAULT false,
    deleted_at      TIMESTAMP(0),
    purge_after     TIMESTAMP(0),
    created_at      TIMESTAMP(0)    NOT NULL DEFAULT now()
);
