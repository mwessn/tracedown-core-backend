CREATE TABLE services (
    id                      UUID            PRIMARY KEY,
    project_id              UUID            NOT NULL REFERENCES projects(id),
    name                    VARCHAR(128)    NOT NULL,
    label                   VARCHAR(32),
    script                  TEXT            NOT NULL DEFAULT '',
    schedule                VARCHAR(16)     NOT NULL DEFAULT '*/5 * * * *',
    probe_mode              VARCHAR(16)     NOT NULL DEFAULT 'consecutive'
                            CHECK (probe_mode IN ('consecutive', 'simultaneous', 'random')),
    queue_policy            VARCHAR(16)     NOT NULL DEFAULT 'skip'
                            CHECK (queue_policy IN ('skip', 'enqueue_once')),
    service_window          VARCHAR(256),
    is_active               BOOLEAN         NOT NULL DEFAULT true,
    last_status             VARCHAR(8)      CHECK (last_status IN ('success', 'failure', 'timeout')),
    last_status_since       TIMESTAMP(0),
    last_status_consecutive INTEGER         NOT NULL DEFAULT 0,
    last_run_id             UUID,
    version                 INTEGER         NOT NULL DEFAULT 1,
    deleted                 BOOLEAN         NOT NULL DEFAULT false,
    deleted_at              TIMESTAMP(0),
    purge_after             TIMESTAMP(0),
    created_at              TIMESTAMP(0)    NOT NULL DEFAULT now()
);
-- last_run_id FK to probe_results added by V1_999
