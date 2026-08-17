CREATE TABLE projects (
    id                  UUID            PRIMARY KEY,
    workspace_id        UUID            NOT NULL REFERENCES workspaces(id),
    name                VARCHAR(128)    NOT NULL,
    is_active           BOOLEAN         NOT NULL DEFAULT true,
    cover_image_url     VARCHAR(128),
    deleted             BOOLEAN         NOT NULL DEFAULT false,
    deleted_at          TIMESTAMP(0),
    purge_after         TIMESTAMP(0),
    created_at          TIMESTAMP(0)    NOT NULL DEFAULT now()
);
