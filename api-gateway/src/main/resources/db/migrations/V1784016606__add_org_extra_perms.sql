-- Adds an open extension map for org-level permission sections registered by
-- additional modules at runtime. Built-in sections keep their dedicated
-- SMALLINT columns; sections registered by other modules store their access
-- levels here as a flat JSON object of { "<sectionKey>": <0|1|2> }.
--
-- Defaults to an empty object so existing rows and inserts that predate any
-- registered extension sections carry no extra grants.

ALTER TABLE org_users  ADD COLUMN org_extra_perms JSONB NOT NULL DEFAULT '{}';
ALTER TABLE org_groups ADD COLUMN org_extra_perms JSONB NOT NULL DEFAULT '{}';
