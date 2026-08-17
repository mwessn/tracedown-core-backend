CREATE TABLE service_allowed_agents (
    id                      UUID            PRIMARY KEY,
    service_id              UUID            NOT NULL REFERENCES services(id),
    probe_agent_id          BIGINT          NOT NULL REFERENCES probe_agents(id),
    UNIQUE (service_id, probe_agent_id)
);
