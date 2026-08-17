CREATE TABLE probe_steps (
    id                          UUID            PRIMARY KEY,
    probe_result_id             UUID            NOT NULL REFERENCES probe_results(id),
    step_num                    SMALLINT        NOT NULL,
    request_url                 VARCHAR(256)    NOT NULL,
    status_code                 SMALLINT,
    response_time_ms            INTEGER,
    dns_ms                      INTEGER,
    connect_ms                  INTEGER,
    tls_ms                      INTEGER,
    ttfb_ms                     INTEGER,
    transfer_ms                 INTEGER,
    response_size_bytes         INTEGER,
    assertion_results           JSONB,
    extracted_variables         JSONB,
    headers                     JSONB,
    cookies                     JSONB,
    error                       TEXT,
    response_body_storage_url   TEXT,
    body_not_stored_reason      VARCHAR(64),
    created_at                  TIMESTAMP(0)    NOT NULL DEFAULT now()
);

CREATE INDEX idx_probe_steps_result ON probe_steps(probe_result_id);
