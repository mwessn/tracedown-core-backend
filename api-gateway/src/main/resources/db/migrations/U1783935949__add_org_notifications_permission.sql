-- Reverts the org-level notifications permission section.

UPDATE org_users
   SET permission_cache = permission_cache #- '{org,notifications}'
 WHERE permission_cache IS NOT NULL;

ALTER TABLE org_users  DROP COLUMN org_notifications;
ALTER TABLE org_groups DROP COLUMN org_notifications;
