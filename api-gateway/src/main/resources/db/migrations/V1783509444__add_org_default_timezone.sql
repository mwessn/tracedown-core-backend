-- Org-wide default timezone (IANA name). Used wherever a timezone is
-- optional — service maintenance windows evaluate in this zone unless the
-- window spec carries its own.
ALTER TABLE organizations ADD COLUMN default_timezone VARCHAR(64) NOT NULL DEFAULT 'UTC';
