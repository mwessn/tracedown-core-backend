-- Undo migration
--
-- WARNING: dropping this table destroys every organization's data-encryption
-- key. Secret variable values already re-encrypted to the envelope format
-- ("v2:" prefix) become permanently undecryptable. Only run this against an
-- installation that has no envelope-encrypted secrets.
DROP TABLE IF EXISTS org_encryption_keys;
