CREATE TABLE agent_health_checks (
    id                      UUID            PRIMARY KEY,
    probe_agent_id          BIGINT          NOT NULL REFERENCES probe_agents(id),
    challenge_id            VARCHAR(64)     NOT NULL UNIQUE,
    challenged_at           TIMESTAMP(0)    NOT NULL,
    responded_at            TIMESTAMP(0),
    round_trip_ms           INTEGER,
    result                  VARCHAR(16)     NOT NULL
                            CHECK (result IN ('pass', 'fail', 'wrong_token', 'timeout')),
    created_at              TIMESTAMP(0)    NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_health_checks_agent ON agent_health_checks(probe_agent_id, created_at DESC);
