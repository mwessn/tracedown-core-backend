-- Adds a dedicated org-level "notifications" permission section that gates
-- notification-template management (previously folded under "settings").
--
-- Backfill: existing members/groups keep template access by copying their
-- current settings level into the new notifications column. The cached
-- effective permissions carry the same copy so access is preserved without
-- waiting for a cache recompute (the cache stores effective, not direct,
-- levels — so copying org.settings there mirrors group-inherited access too).

ALTER TABLE org_users  ADD COLUMN org_notifications SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE org_groups ADD COLUMN org_notifications SMALLINT NOT NULL DEFAULT 0;

UPDATE org_users  SET org_notifications = org_settings;
UPDATE org_groups SET org_notifications = org_settings;

UPDATE org_users
   SET permission_cache = jsonb_set(
           permission_cache, '{org,notifications}', permission_cache->'org'->'settings')
 WHERE permission_cache IS NOT NULL
   AND permission_cache->'org' ? 'settings';
