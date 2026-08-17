# End-to-End Tests

Cross-service integration tests that verify the full Tracedown probe
pipeline works end-to-end. These run after module tests pass in CI.

## Why these exist

Module tests (in `api-gateway/`, `probe-scheduler/`) test each service
in isolation with mocked or testcontainer dependencies. They verify API
correctness, database operations, and business logic within a single
service boundary.

These e2e tests verify what module tests **cannot**:

- **Service creation → scheduler pickup**: a service created via the
  gateway API is picked up by the probe-scheduler's consistency sweep
  and scheduled for execution.
- **Probe dispatch → agent execution**: the scheduler dispatches a real
  Lace script to a real probe agent over HTTP, and the agent executes
  it against a real endpoint.
- **Result flow**: the agent returns a ProbeResult, the scheduler
  publishes it to Redis, and it can be consumed from the queue.
- **Health challenge pipeline**: the scheduler generates a challenge,
  stores a token in Redis, the agent fetches it from the gateway's
  token endpoint via a Lace script, and returns it for validation.
- **Agent bootstrap → registration**: the full mTLS bootstrap flow
  from CLI token generation through agent startup and certificate
  exchange.

## What they test

1. **Full probe lifecycle**: login → create workspace/project/service →
   set script → enable → wait for probe to run → verify result in Redis.
2. **Health challenge**: verify `agent_health_checks` table receives a
   passing result from the automated challenge-response flow.
3. **Agent registration**: bootstrap token → agent startup → mTLS
   certificate exchange → agent appears in `probe_agents` table.

## How to run

```bash
cd core/backend/e2e
./run.sh
```

Requires Docker. The script:
1. Builds the gateway and scheduler via `installDist`
2. Builds the probe agent Docker image from local source
3. Starts PostgreSQL, Redis, schema-migrator, gateway, scheduler, and
   agent via `docker-compose.e2e.yml`
4. Waits for all services to be healthy
5. Runs the Python test script against the gateway API
6. Tears down all containers on exit

## In CI

```yaml
- name: E2E tests
  run: cd core/backend/e2e && ./run.sh
```

The probe agent is built from the local `core/probe-agent/` directory.
In production CI, it will be pulled from its published package instead.
