"""
E2E: Metrics-service Prometheus scrape endpoint.

Seeds a grafana_integration, pushes a metric nudge via Redis pub/sub,
then scrapes the Prometheus endpoint to verify metrics are returned.
Also tests authentication failure cases.
"""

import hashlib
import json
import os
import time
import urllib.error
import urllib.request

import test_helpers as h

METRICS_URL = os.environ.get("METRICS_URL", "http://localhost:18086")

# Module-level state
_integration_id = None
_test_token = "test-token-e2e-metrics"


def _metrics_request(path, token=None):
    """Make an HTTP request directly to the metrics service."""
    url = f"{METRICS_URL}{path}"
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        body_text = e.read().decode() if e.fp else ""
        return e.code, body_text


@h.log_test("Seed grafana integration and push metric nudge", reset_db_before=False)
def test_seed_integration_and_nudge():
    global _integration_id

    if not h.svc_id:
        h.skip_test("No service ID -- test_service_lifecycle must run first")

    # Look up org_id from the workspace
    rows = h.query_db(
        f"SELECT organization_id FROM workspaces WHERE id = '{h.ws_id}'"
    )
    assert rows, "Could not find workspace to get org_id"
    org_id = rows[0]
    print(f"  org_id={org_id[:8]}...")

    # Generate a UUID for the integration
    id_rows = h.query_db("SELECT gen_random_uuid()::text")
    _integration_id = id_rows[0]

    # Build config JSON. The config stores only the token's SHA-256
    # (tokenHash) — mirrors what the gateway writes on create.
    token_hash = hashlib.sha256(_test_token.encode()).hexdigest()
    config = json.dumps({
        "tokenHash": token_hash,
        "scope": {"type": "all"},
        "labels": {"env": "e2e"},
    })

    # Seed the grafana_integration directly in DB. Integrations are
    # project-scoped, so project_id (NOT NULL) is required.
    h.query_db(
        f"INSERT INTO grafana_integrations (id, organization_id, project_id, name, config, enabled, deleted, created_at) "
        f"VALUES ('{_integration_id}', '{org_id}', '{h.proj_id}', 'E2E Metrics', '{config}'::jsonb, true, false, now())"
    )
    print(f"  Seeded grafana_integration id={_integration_id[:8]}...")

    # Push a metric nudge to Redis to populate data in metrics-service
    nudge_payload = json.dumps({
        "orgId": org_id,
        "serviceId": h.svc_id,
        "status": "success",
        "totalResponseMs": 142,
    })
    result = h.query_redis_args(["PUBLISH", "notify:nudge", nudge_payload])
    print(f"  Published notify:nudge (subscribers: {result})")

    # Wait for metrics-service to process the nudge
    time.sleep(8)


@h.log_test("Scrape Prometheus endpoint returns metrics", reset_db_before=False)
def test_scrape_metrics():
    if not _integration_id:
        h.skip_test("No integration ID -- previous test must run first")

    # Retry scrape — nudge may not have been processed yet
    for i in range(10):
        status, body = _metrics_request(f"/metrics/{_integration_id}", token=_test_token)
        if status == 200 and "tracedown_probes_total" in body:
            break
        # Re-publish nudge in case it was missed
        if i == 3:
            nudge = json.dumps({"orgId": "", "serviceId": h.svc_id, "status": "success", "totalResponseMs": 100})
            h.query_redis_args(["PUBLISH", "notify:nudge", nudge])
        time.sleep(3)

    h.assert_status(status, 200, f"body={body[:200]}")
    assert "tracedown_probes_total" in body, (
        f"Expected 'tracedown_probes_total' in response, got: {body[:300]}"
    )
    assert 'env="e2e"' in body, (
        f"Expected custom label env=\"e2e\" in response, got: {body[:300]}"
    )
    print(f"  Prometheus response ({len(body)} chars) contains expected metrics and labels")

    assert "tracedown_service_up" in body, "Expected tracedown_service_up gauge"
    print("  tracedown_service_up gauge present")


@h.log_test("Scrape without token returns 401", reset_db_before=False)
def test_scrape_no_token():
    if not _integration_id:
        h.skip_test("No integration ID -- previous test must run first")

    status, _ = _metrics_request(f"/metrics/{_integration_id}")
    h.assert_status(status, 401, "Expected 401 without token")
    print("  Correctly rejected request without token")


@h.log_test("Scrape with wrong token returns 401", reset_db_before=False)
def test_scrape_wrong_token():
    if not _integration_id:
        h.skip_test("No integration ID -- previous test must run first")

    status, _ = _metrics_request(f"/metrics/{_integration_id}", token="wrong-token-value")
    h.assert_status(status, 401, "Expected 401 with wrong token")
    print("  Correctly rejected request with wrong token")


def get_tests():
    return [
        test_seed_integration_and_nudge,
        test_scrape_metrics,
        test_scrape_no_token,
        test_scrape_wrong_token,
    ]
