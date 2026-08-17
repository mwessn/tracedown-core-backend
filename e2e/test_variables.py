"""
E2E: Variable-hierarchy resolution inside a probe.

Sets a project-scope variable and a service-scope variable, then runs a
probe whose URL interpolates BOTH (`$p.` and `$s.`). A successful probe
proves both scopes resolved correctly (a bad resolution would produce an
invalid URL and fail). Depends on test_service_lifecycle
(uses h.admin_token, h.proj_id).
"""

import time

import test_helpers as h

_svc_id = None


@h.log_test("Set a project-scope variable", reset_db_before=False)
def test_set_project_variable():
    if not h.admin_token or not h.proj_id:
        h.skip_test("No admin_token/proj_id -- test_service_lifecycle must run first")
    status, body = h.api("POST", f"/api/v1/projects/{h.proj_id}/variables",
                         {"key": "e2eBase", "value": "http://testbin:20780"}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    print("  Set $p.e2eBase = http://testbin:20780")


@h.log_test("Create service + set a service-scope variable", reset_db_before=False)
def test_create_service_with_variable():
    global _svc_id
    status, body = h.api("POST", "/api/v1/services",
                         {"projectId": h.proj_id, "name": "E2E Vars", "schedule": "0 0 1 1 *"},
                         h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    _svc_id = body["id"]

    status, body = h.api("POST", f"/api/v1/services/{_svc_id}/variables",
                         {"key": "e2ePath", "value": "status/200"}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    print("  Set $s.e2ePath = status/200")

    # Script interpolates both the project and service variables.
    status, body = h.api("PATCH", f"/api/v1/services/{_svc_id}/script",
                         {"script": 'get("$p.e2eBase/$s.e2ePath").expect(status: 200)',
                          "version": 1}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")

    status, body = h.api("PATCH", f"/api/v1/services/{_svc_id}/toggle",
                         {"isActive": True}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    print(f"  Created service {_svc_id[:8]}... referencing $p.e2eBase/$s.e2ePath")


@h.log_test("Probe succeeds -> both variable scopes resolved", reset_db_before=False)
def test_variables_resolve_in_probe():
    if not _svc_id:
        h.skip_test("No service id")

    status, _ = h.api("POST", f"/api/v1/services/{_svc_id}/run", token=h.admin_token)
    assert status in (200, 202), f"run failed: {status}"

    for i in range(20):
        rows = h.query_db(
            f"SELECT status FROM probe_results WHERE service_id = '{_svc_id}' "
            f"ORDER BY started_at DESC LIMIT 1"
        )
        if rows:
            result_status = rows[0]
            print(f"  Probe result after {i * 2}s: status={result_status}")
            assert result_status == "success", (
                f"Expected success (variables resolved to a valid URL), got '{result_status}'. "
                "A non-success status means $p.e2eBase or $s.e2ePath did not resolve."
            )
            return
        time.sleep(2)
    raise AssertionError("No probe result within 40s of POST /run")


def get_tests():
    return [
        test_set_project_variable,
        test_create_service_with_variable,
        test_variables_resolve_in_probe,
    ]
