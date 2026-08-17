CREATE TABLE org_user_groups (
    id              UUID            PRIMARY KEY,
    org_user_id     UUID            NOT NULL REFERENCES org_users(id),
    org_group_id    UUID            NOT NULL REFERENCES org_groups(id),
    UNIQUE(org_user_id, org_group_id)
);
