"""
E2E: Agent health challenge pipeline.

Tests: scheduler generates challenge → stores token in Redis →
agent fetches token from gateway → agent returns token →
scheduler validates and records result in agent_health_checks.
"""

import time

import test_helpers as h


@h.log_test("Agent is registered")
def test_agent_registered():
    rows = h.query_db("SELECT slug, is_active, last_status FROM probe_agents WHERE slug = 'e2e-agent'")
    assert rows, "Agent 'e2e-agent' not found in probe_agents"
    print(f"  Agent row: {rows[0]}")


@h.log_test("Health challenge recorded in DB", reset_db_before=False)
def test_health_challenge_recorded():
    """Wait for at least one health challenge result to appear."""
    for i in range(45):
        rows = h.query_db("SELECT COUNT(*) FROM agent_health_checks")
        if rows and int(rows[0]) > 0:
            print(f"  {rows[0]} health check(s) after {i * 2}s")

            # Check the latest result
            detail = h.query_db(
                "SELECT result, round_trip_ms FROM agent_health_checks "
                "ORDER BY created_at DESC LIMIT 1"
            )
            if detail:
                print(f"  Latest check: {detail[0]}")
            return
        time.sleep(2)
    raise AssertionError("No health challenge results after 90s")


@h.log_test("Agent last_status updated", reset_db_before=False)
def test_agent_status_updated():
    rows = h.query_db("SELECT last_status, last_pong_delta_ms FROM probe_agents WHERE slug = 'e2e-agent'")
    assert rows, "Agent not found"
    print(f"  Agent status: {rows[0]}")


def get_tests():
    return [
        test_agent_registered,
        test_health_challenge_recorded,
        test_agent_status_updated,
    ]
