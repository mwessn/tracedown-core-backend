"""
E2E: Usage metering API.

After probes have run, the per-service usage endpoint reports request and
network byte counts sourced from the Redis usage buckets (written by
metrics-service on notify:nudge). Depends on test_service_lifecycle
(uses h.admin_token, h.svc_id).
"""

import time

import test_helpers as h

USAGE_FIELDS = ("windowHours", "requests", "ingressBytes", "egressBytes", "agentEgressBytes")


@h.log_test("Service usage reports requests after probes", reset_db_before=False)
def test_service_usage():
    if not h.admin_token or not h.svc_id:
        h.skip_test("No admin_token/svc_id -- test_service_lifecycle must run first")

    # Usage buckets are populated asynchronously by metrics-service; retry.
    last = None
    for i in range(15):
        status, body = h.api("GET", f"/api/v1/services/{h.svc_id}/usage?hours=24",
                             token=h.admin_token)
        h.assert_status(status, 200, f"body={body}")
        last = body
        for f in USAGE_FIELDS:
            assert f in body, f"Missing usage field {f!r} in {body}"
        if body["requests"] >= 1:
            print(f"  usage after {i * 2}s: requests={body['requests']}, "
                  f"ingress={body['ingressBytes']}, egress={body['egressBytes']}, "
                  f"agentEgress={body['agentEgressBytes']}, window={body['windowHours']}h")
            assert body["windowHours"] <= 24, f"window should be capped, got {body['windowHours']}"
            return
        time.sleep(2)
    raise AssertionError(f"Usage 'requests' never reached >= 1 (last response: {last})")


@h.log_test("Usage bucket exists in Redis", reset_db_before=False)
def test_usage_bucket_in_redis():
    if not h.svc_id:
        h.skip_test("No svc_id")
    keys = h.query_redis_args(["KEYS", f"metrics:usage:svc:{h.svc_id}:h:*"])
    print(f"  usage bucket keys: {keys[:160]}")
    assert keys and f"metrics:usage:svc:{h.svc_id}" in keys, \
        "No metrics:usage:svc bucket found in Redis"


@h.log_test("Usage requires access (401 without token)", reset_db_before=False)
def test_usage_requires_auth():
    if not h.svc_id:
        h.skip_test("No svc_id")
    status, _ = h.api("GET", f"/api/v1/services/{h.svc_id}/usage?hours=24")
    assert status == 401, f"Expected 401 without a token, got {status}"
    print("  Correctly rejected unauthenticated usage request")


def get_tests():
    return [
        test_service_usage,
        test_usage_bucket_in_redis,
        test_usage_requires_auth,
    ]
