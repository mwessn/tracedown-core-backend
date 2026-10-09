-- Runs are no longer told apart by what started them. Nothing outside the
-- database refers to the column.
ALTER TABLE probe_results DROP COLUMN trigger;
