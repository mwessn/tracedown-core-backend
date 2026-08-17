"""
E2E: On-demand "run now" trigger.

Creates a service whose schedule won't fire during the test, then POSTs
/run and verifies a probe result appears immediately — proving the
probe:trigger pub/sub path (not the cron). Depends on test_service_lifecycle
(uses h.admin_token, h.proj_id).
"""

import time

import test_helpers as h

_svc_id = None


@h.log_test("Create a service that won't auto-fire", reset_db_before=False)
def test_create_idle_service():
    global _svc_id
    if not h.admin_token or not h.proj_id:
        h.skip_test("No admin_token/proj_id -- test_service_lifecycle must run first")

    # Yearly cron (Jan 1) so the scheduler never auto-dispatches during the run.
    status, body = h.api("POST", "/api/v1/services",
                         {"projectId": h.proj_id, "name": "E2E RunNow", "schedule": "0 0 1 1 *"},
                         h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    _svc_id = body["id"]

    status, body = h.api("PATCH", f"/api/v1/services/{_svc_id}/script",
                         {"script": 'get("http://testbin:20780/status/200").expect(status: 200)',
                          "version": 1}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")

    status, body = h.api("PATCH", f"/api/v1/services/{_svc_id}/toggle",
                         {"isActive": True}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    print(f"  Created idle service {_svc_id[:8]}... (yearly cron, active)")


@h.log_test("POST /run triggers an immediate probe", reset_db_before=False)
def test_run_now_triggers_probe():
    if not _svc_id:
        h.skip_test("No service id -- create step must run first")

    rows = h.query_db(f"SELECT count(*) FROM probe_results WHERE service_id = '{_svc_id}'")
    before = int(rows[0]) if rows else 0
    print(f"  Results before /run: {before}")

    status, body = h.api("POST", f"/api/v1/services/{_svc_id}/run", token=h.admin_token)
    assert status in (200, 202), f"Expected 202, got {status}: {body}"
    print(f"  Run accepted (status={status})")

    for i in range(20):
        rows = h.query_db(f"SELECT count(*) FROM probe_results WHERE service_id = '{_svc_id}'")
        after = int(rows[0]) if rows else 0
        if after > before:
            print(f"  Probe result appeared {i * 2}s after /run (before={before}, after={after})")
            return
        time.sleep(2)
    raise AssertionError("No probe result appeared within 40s of POST /run")


@h.log_test("Run-now requires authentication", reset_db_before=False)
def test_run_now_requires_auth():
    if not _svc_id:
        h.skip_test("No service id")
    status, _ = h.api("POST", f"/api/v1/services/{_svc_id}/run")
    assert status == 401, f"Expected 401 without a token, got {status}"
    print("  Correctly rejected unauthenticated run")


def get_tests():
    return [
        test_create_idle_service,
        test_run_now_triggers_probe,
        test_run_now_requires_auth,
    ]
