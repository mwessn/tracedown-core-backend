-- Adds a dedicated org-level "admin" permission section that gates root-level
-- org identity/policy (name, timezone, TOTP enforcement) and the danger zone,
-- splitting it out of the broader "settings" section (which still gates org
-- variables, agents, warning log, etc.).
--
-- Backfill: existing members/groups keep their current reach by copying their
-- settings level into admin (renaming the org was previously a settings-gated
-- action). The cached effective permissions carry the same copy so nothing
-- loses access before a recompute.

ALTER TABLE org_users  ADD COLUMN org_admin SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE org_groups ADD COLUMN org_admin SMALLINT NOT NULL DEFAULT 0;

UPDATE org_users  SET org_admin = org_settings;
UPDATE org_groups SET org_admin = org_settings;

UPDATE org_users
   SET permission_cache = jsonb_set(
           permission_cache, '{org,admin}', permission_cache->'org'->'settings')
 WHERE permission_cache IS NOT NULL
   AND permission_cache->'org' ? 'settings';
