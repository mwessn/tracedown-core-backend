-- Reverts the org-level admin permission section.

UPDATE org_users
   SET permission_cache = permission_cache #- '{org,admin}'
 WHERE permission_cache IS NOT NULL;

ALTER TABLE org_users  DROP COLUMN org_admin;
ALTER TABLE org_groups DROP COLUMN org_admin;
