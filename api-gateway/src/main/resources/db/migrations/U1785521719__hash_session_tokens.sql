-- Reverts the column rename. Hashed values cannot be turned back into raw
-- tokens, so they are cleared — affected sessions require a fresh login.
DROP INDEX IF EXISTS idx_sessions_active_token;

UPDATE sessions SET session_token_hash = NULL WHERE session_token_hash IS NOT NULL;

ALTER TABLE sessions RENAME COLUMN session_token_hash TO session_token;

CREATE INDEX idx_sessions_active_token ON sessions(session_token) WHERE status = 'active';
