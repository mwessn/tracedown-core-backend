"""
E2E: Full service probe lifecycle.

Tests the cross-service flow: gateway API -> DB -> scheduler pickup ->
agent dispatch -> result-ingestor -> DB persistence -> disable.
"""

import time

import test_helpers as h


@h.log_test("Login as demo admin")
def test_login():
    status, body = h.api("POST", "/api/v1/auth/login", {
        "email": "admin@tracedown.dev",
        "password": "Down2trace!",
    })
    h.assert_status(status, 200, f"body={body}")
    h.admin_token = body["token"]


@h.log_test("Create service, set script, enable, and verify probe executes", reset_db_before=False)
def test_full_probe_lifecycle():
    # Create workspace
    status, body = h.api("POST", "/api/v1/workspaces",
                         {"name": "E2E Workspace"}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    h.ws_id = body["id"]

    # Create project
    status, body = h.api("POST", "/api/v1/projects",
                         {"workspaceId": h.ws_id, "name": "E2E Project"}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    h.proj_id = body["id"]

    # Create service with 1-minute schedule
    status, body = h.api("POST", "/api/v1/services",
                         {"projectId": h.proj_id, "name": "E2E Probe", "schedule": "* * * * *"}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    h.svc_id = body["id"]

    svc_path = f"/api/v1/services/{h.svc_id}"

    # Set script
    status, body = h.api("PATCH", f"{svc_path}/script", {
        "script": 'get("http://testbin:20780/status/200").expect(status: 200)',
        "version": 1,
    }, h.admin_token)
    h.assert_status(status, 200, f"body={body}")

    # Enable
    status, body = h.api("PATCH", f"{svc_path}/toggle",
                         {"isActive": True}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")

    # Wait for result to appear in the DB (consumed by result-ingestor)
    for i in range(90):
        rows = h.query_db(
            f"SELECT status, run_duration_ms FROM probe_results "
            f"WHERE service_id = '{h.svc_id}' ORDER BY started_at DESC LIMIT 1"
        )
        if rows:
            parts = rows[0].split("|")
            status_val = parts[0]
            duration = parts[1]
            print(f"  Result persisted in DB after {i * 2}s (status={status_val}, duration={duration}ms)")
            assert status_val == "success", f"Expected success, got {status_val}"
            return
        time.sleep(2)
    raise AssertionError("No probe result in DB after 180s")


@h.log_test("Disable service and verify scheduler stops dispatching", reset_db_before=False)
def test_disable_stops_probes():
    svc_path = f"/api/v1/services/{h.svc_id}"

    # Count current results before disabling
    rows = h.query_db(
        f"SELECT count(*) FROM probe_results WHERE service_id = '{h.svc_id}'"
    )
    count_before = int(rows[0]) if rows else 0
    print(f"  Results before disable: {count_before}")

    # Disable the service
    status, body = h.api("PATCH", f"{svc_path}/toggle",
                         {"isActive": False}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    assert body["isActive"] is False, f"Expected isActive=false, got {body['isActive']}"

    # Wait longer than one cron tick (>60s). If the scheduler correctly
    # unscheduled the service via the nudge, no new results should appear.
    print("  Waiting 75s to verify no new probes fire...")
    time.sleep(75)

    rows = h.query_db(
        f"SELECT count(*) FROM probe_results WHERE service_id = '{h.svc_id}'"
    )
    count_after = int(rows[0]) if rows else 0
    print(f"  Results after disable: {count_after}")

    assert count_after == count_before, (
        f"New probe results appeared after disable: {count_after - count_before} new"
    )
    print("  No probes dispatched for disabled service")


def get_tests():
    return [
        test_login,
        test_full_probe_lifecycle,
        test_disable_stops_probes,
    ]
