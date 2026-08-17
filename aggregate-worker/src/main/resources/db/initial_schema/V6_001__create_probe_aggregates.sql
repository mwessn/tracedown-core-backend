CREATE TABLE probe_aggregates (
    id              UUID            PRIMARY KEY,
    service_id      UUID            NOT NULL REFERENCES services(id),
    probe_agent_id  BIGINT          REFERENCES probe_agents(id),
    bucket_start    TIMESTAMP       NOT NULL,
    bucket_type     VARCHAR(8)      NOT NULL CHECK (bucket_type IN ('hourly', 'daily')),
    p50_ms          INTEGER,
    p95_ms          INTEGER,
    p99_ms          INTEGER,
    error_rate      REAL,
    uptime_pct      REAL,
    probe_count     INTEGER         NOT NULL DEFAULT 0
);

CREATE INDEX idx_probe_aggregates_service ON probe_aggregates(service_id, bucket_start DESC);
CREATE UNIQUE INDEX idx_probe_aggregates_unique ON probe_aggregates(service_id, probe_agent_id, bucket_start, bucket_type);
