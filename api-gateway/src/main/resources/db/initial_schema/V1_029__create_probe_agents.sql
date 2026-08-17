CREATE TABLE probe_agents (
    id                      BIGSERIAL       PRIMARY KEY,
    slug                    VARCHAR(64)     NOT NULL UNIQUE,
    label                   VARCHAR(64)     NOT NULL,
    agent_uri               VARCHAR(255)    NOT NULL,
    public_key              TEXT            NOT NULL,
    is_active               BOOLEAN         NOT NULL DEFAULT true,
    last_ping               TIMESTAMP(0)    NOT NULL,
    last_status             VARCHAR(8)      NOT NULL
                            CHECK (last_status IN ('success', 'failure', 'timeout')),
    last_ping_delay_ms      INTEGER         NOT NULL,
    last_pong_delta_ms      INTEGER         NOT NULL,
    created_at              TIMESTAMP(0)    NOT NULL DEFAULT now()
);
