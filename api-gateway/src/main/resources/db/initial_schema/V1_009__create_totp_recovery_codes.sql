CREATE TABLE totp_recovery_codes (
    id              UUID            PRIMARY KEY,
    user_id         UUID            NOT NULL REFERENCES users(id),
    code_hash       VARCHAR(255)    NOT NULL,
    used            BOOLEAN         NOT NULL DEFAULT false,
    used_at         TIMESTAMP(0),
    created_at      TIMESTAMP(0)    NOT NULL DEFAULT now()
);
