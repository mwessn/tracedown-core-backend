-- Reverts the org-level extension-section permission map.

ALTER TABLE org_users  DROP COLUMN org_extra_perms;
ALTER TABLE org_groups DROP COLUMN org_extra_perms;
