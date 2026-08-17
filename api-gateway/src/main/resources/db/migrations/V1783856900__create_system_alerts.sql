-- Platform-raised operational alerts (dispatch capacity, agent health, …)
-- surfaced as dismissable banners to org admins. One IMMUTABLE row per
-- "episode" of a (org, alert_type, subject) condition: while it keeps
-- recurring, last_seen_at refreshes; once it goes quiet past the active
-- window, the next occurrence inserts a new row. History stays queryable
-- (settings → warning log).
CREATE TABLE system_alerts (
    id              UUID            PRIMARY KEY,
    organization_id UUID            NOT NULL REFERENCES organizations(id),
    alert_type      VARCHAR(64)     NOT NULL,
    -- Natural key of the affected thing (agent slug, service id, …); '' when global.
    subject         VARCHAR(128)    NOT NULL DEFAULT '',
    severity        VARCHAR(16)     NOT NULL DEFAULT 'warning',
    data            JSONB,
    created_at      TIMESTAMP(0)    NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMP(0)    NOT NULL DEFAULT now()
);

CREATE INDEX idx_system_alerts_org_created ON system_alerts(organization_id, created_at DESC);
CREATE INDEX idx_system_alerts_org_type_subject ON system_alerts(organization_id, alert_type, subject, last_seen_at DESC);

-- Per-user dismissals — tied to one episode row (a new episode is a new
-- row, so it is undismissed by construction).
CREATE TABLE system_alert_dismissals (
    id           UUID            PRIMARY KEY,
    alert_id     UUID            NOT NULL REFERENCES system_alerts(id) ON DELETE CASCADE,
    user_id      UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    dismissed_at TIMESTAMP(0)    NOT NULL DEFAULT now(),
    UNIQUE (alert_id, user_id)
);
