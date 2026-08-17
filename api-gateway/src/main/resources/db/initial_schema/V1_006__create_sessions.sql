CREATE TABLE sessions (
    id              UUID            PRIMARY KEY,
    user_id         UUID            NOT NULL REFERENCES users(id),
    organization_id UUID            REFERENCES organizations(id),
    -- Nullable: a session is tokenless until it reaches 'active'. A pending row
    -- (awaiting TOTP) carries no bearer token, so token-keyed lookups cannot
    -- match it — the lifecycle exclusion is structural, not a remembered filter.
    session_token   VARCHAR(255)    UNIQUE,
    -- Session lifecycle: 'pending_totp' (password verified, awaiting 2FA) | 'active'.
    status          VARCHAR(20)     NOT NULL DEFAULT 'active',
    -- Failed TOTP attempts against a pending row; locks the row at the cap.
    totp_attempt_count INT          NOT NULL DEFAULT 0,
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(512),
    expires_at      TIMESTAMP       NOT NULL,
    last_active_at  TIMESTAMP       NOT NULL DEFAULT now(),
    revoked         BOOLEAN         NOT NULL DEFAULT false,
    created_at      TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE INDEX idx_sessions_user_id ON sessions(user_id);
-- Hot path for session validation: only active sessions are looked up by token.
CREATE INDEX idx_sessions_active_token ON sessions(session_token) WHERE status = 'active';
