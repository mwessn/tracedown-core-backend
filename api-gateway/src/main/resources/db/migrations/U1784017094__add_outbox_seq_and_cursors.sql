-- Undo migration

DROP TABLE outbox_cursors;

DROP INDEX idx_outbox_seq;

ALTER TABLE outbox DROP COLUMN seq;
