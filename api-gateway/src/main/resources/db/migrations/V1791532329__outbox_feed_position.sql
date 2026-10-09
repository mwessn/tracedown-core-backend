-- Forward migration
--
-- What the key-authenticated API's event feed needs to read the outbox by
-- position without ever losing a row it promised.
--
-- inserted_at: when a row was written, by the database's clock at the INSERT.
-- `seq` is handed out at INSERT and becomes visible at COMMIT, so a reader can
-- see seq 106 while 105 sits in a transaction that has not committed yet; a
-- reader that moved past 105 would never see it. The feed stops before such a
-- hole and waits for it — but only while the row after it is young, because a
-- hole a rollback left never fills. created_at cannot tell: a probe result's
-- row carries the run's start, not the write. A timestamptz, so the age is the
-- same whichever process's session (and time zone) wrote the row.
--
-- Added without a default and given it afterwards, so no existing row is
-- rewritten: old rows stay NULL, which the feed reads as "old".
ALTER TABLE outbox ADD COLUMN inserted_at TIMESTAMPTZ;
ALTER TABLE outbox ALTER COLUMN inserted_at SET DEFAULT clock_timestamp();

-- outbox_retention: the highest seq the purge has deleted. The purge does not
-- trim a prefix — an unpublished probe result outlives newer rows — so the
-- lowest seq left says nothing about what is gone; this does. A feed cursor
-- below it may have missed a row and is refused rather than served with a
-- silent hole. One row, only ever raised (by the purge, in its own DELETE
-- statement).
CREATE TABLE outbox_retention (
    id              SMALLINT    PRIMARY KEY CHECK (id = 1),
    purged_through  BIGINT      NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP   NOT NULL DEFAULT now()
);

-- No cursor exists yet — the feed is new with this migration — so nothing
-- handed out can lie below what earlier purges took; the mark starts at 0 and
-- the next purge raises it.
INSERT INTO outbox_retention (id, purged_through, updated_at) VALUES (1, 0, now());
