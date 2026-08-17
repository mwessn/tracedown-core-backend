CREATE TABLE outbox (
    id              UUID            PRIMARY KEY,
    aggregate_type  VARCHAR(32)     NOT NULL,
    aggregate_id    UUID            NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    payload         JSONB           NOT NULL,
    published       BOOLEAN         NOT NULL DEFAULT false,
    created_at      TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published = false;
