-- Store only a SHA-256 digest of each session's bearer token, never the raw
-- token. A DB or backup read then no longer yields live credentials.
--
-- Existing rows hold raw tokens; there is no reversible way to convert them to
-- digests, so they are cleared — those sessions simply require a fresh login.
ALTER TABLE sessions RENAME COLUMN session_token TO session_token_hash;

-- The old index referenced the pre-rename column name.
DROP INDEX IF EXISTS idx_sessions_active_token;

-- Clear pre-existing plaintext tokens: a raw token can never match a hashed
-- lookup, so leaving them would only be dead, sensitive data.
UPDATE sessions SET session_token_hash = NULL WHERE session_token_hash IS NOT NULL;

-- Hot path for session validation: only active sessions are looked up by hash.
CREATE INDEX idx_sessions_active_token ON sessions(session_token_hash) WHERE status = 'active';
