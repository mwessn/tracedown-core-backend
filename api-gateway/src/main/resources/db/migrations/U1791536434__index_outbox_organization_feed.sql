-- Undo migration
--
-- The feed reads one organization's rows by a full walk of the outbox without
-- it. On a large outbox, prefer DROP INDEX CONCURRENTLY by hand, outside a
-- transaction.
DROP INDEX IF EXISTS idx_outbox_organization_feed;
