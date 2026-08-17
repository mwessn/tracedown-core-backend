ALTER TABLE users
    ADD CONSTRAINT fk_users_selected_org_id
    FOREIGN KEY (selected_org_id) REFERENCES organizations(id);

-- last_run_id FK to probe_results: added in V3_999 (after probe_results is created by V3_001).
