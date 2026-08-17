-- Forward migration

-- Monotonic ordering key for the outbox so independent consumers can walk the
-- log by offset. The primary key is a random UUID, which is not orderable, so
-- a BIGINT identity is added for cursoring. Existing rows are backfilled by the
-- identity sequence; new rows populate automatically, so producers that omit it
-- (result-ingestor, resource-event emits) are unaffected.
ALTER TABLE outbox ADD COLUMN seq BIGSERIAL;

CREATE UNIQUE INDEX idx_outbox_seq ON outbox(seq);

-- Per-consumer read offsets. Each consumer tracks the highest seq it has fully
-- processed; retention never deletes rows above the minimum offset here.
CREATE TABLE outbox_cursors (
    consumer_name   VARCHAR(128)    PRIMARY KEY,
    last_id         BIGINT          NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP       NOT NULL DEFAULT now()
);
