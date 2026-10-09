-- Undo migration
--
-- Drops what the event feed reads. Nothing else uses either: the purge raises
-- the mark in the same statement as its DELETE, so roll the aggregate-worker
-- back first or its purge fails on the missing table; and the gateway's feed
-- answers 500 without them, so roll it back first too. Feed cursors handed out
-- before are just positions and stay valid if this is applied again — but the
-- mark restarts from the rows present then.
DROP TABLE IF EXISTS outbox_retention;

ALTER TABLE outbox DROP COLUMN IF EXISTS inserted_at;
