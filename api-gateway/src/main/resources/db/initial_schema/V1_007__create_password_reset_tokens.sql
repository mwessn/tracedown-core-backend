CREATE TABLE password_reset_tokens (
    id              UUID            PRIMARY KEY,
    user_id         UUID            NOT NULL REFERENCES users(id),
    token_hash      VARCHAR(255)    NOT NULL,
    expires_at      TIMESTAMP       NOT NULL,
    used            BOOLEAN         NOT NULL DEFAULT false,
    created_at      TIMESTAMP       NOT NULL DEFAULT now()
);
