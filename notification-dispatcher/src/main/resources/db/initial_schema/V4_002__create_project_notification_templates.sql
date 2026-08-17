CREATE TABLE project_notification_templates (
    id                          UUID    PRIMARY KEY,
    notification_template_id    UUID    NOT NULL REFERENCES notification_templates(id),
    project_id                  UUID    NOT NULL REFERENCES projects(id),
    UNIQUE(notification_template_id, project_id)
);
