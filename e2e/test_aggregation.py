"""
E2E: Aggregate worker — hourly aggregation.

Verifies that the aggregate worker computes hourly probe_aggregates
from raw probe_results. Depends on test_service_lifecycle having
created a service and at least one probe result.

In E2E, the worker's job intervals are set to 5s so aggregates appear quickly.
"""

import time

import test_helpers as h


@h.log_test("Hourly aggregates computed for service", reset_db_before=False)
def test_hourly_aggregates():
    if not h.svc_id:
        h.skip_test("No service ID — test_service_lifecycle must run first")

    # Verify probe_results exist
    result_rows = h.query_db(
        f"SELECT COUNT(*) FROM probe_results WHERE service_id = '{h.svc_id}'"
    )
    result_count = int(result_rows[0]) if result_rows else 0
    print(f"  probe_results count for service: {result_count}")

    # Backdate one result to the previous hour so the aggregation window picks it up.
    # The hourly aggregation only processes COMPLETED hours (not the current one).
    h.query_db(
        f"UPDATE probe_results SET started_at = date_trunc('hour', now()) - interval '1 hour' "
        f"WHERE id = (SELECT id FROM probe_results WHERE service_id = '{h.svc_id}' ORDER BY started_at DESC LIMIT 1)"
    )
    print("  Backdated one result to previous hour for aggregation")

    # Worker runs aggregation every 5s in E2E. Poll for up to 60s.
    for i in range(30):
        rows = h.query_db(
            f"SELECT probe_count, p50_ms, uptime_pct, bucket_type "
            f"FROM probe_aggregates "
            f"WHERE service_id = '{h.svc_id}' "
            f"  AND bucket_type = 'hourly' "
            f"  AND probe_agent_id IS NOT NULL "
            f"ORDER BY bucket_start DESC LIMIT 1"
        )
        if rows:
            parts = rows[0].split("|")
            probe_count = int(parts[0])
            p50_ms = int(parts[1])
            uptime_pct = float(parts[2])
            bucket_type = parts[3]
            print(f"  Aggregate found after {i * 2}s: count={probe_count}, p50={p50_ms}ms, uptime={uptime_pct}, type={bucket_type}")
            assert probe_count >= 1, f"Expected probe_count >= 1, got {probe_count}"
            assert p50_ms > 0, f"Expected p50_ms > 0, got {p50_ms}"
            assert uptime_pct > 0, f"Expected uptime_pct > 0, got {uptime_pct}"
            return
        time.sleep(2)

    raise AssertionError("No hourly aggregate in probe_aggregates after 30s")


@h.log_test("All-agents rollup aggregate exists", reset_db_before=False)
def test_all_agents_rollup():
    if not h.svc_id:
        h.skip_test("No service ID — test_service_lifecycle must run first")

    # The rollup row has probe_agent_id IS NULL
    rows = h.query_db(
        f"SELECT probe_count, p50_ms "
        f"FROM probe_aggregates "
        f"WHERE service_id = '{h.svc_id}' "
        f"  AND bucket_type = 'hourly' "
        f"  AND probe_agent_id IS NULL "
        f"ORDER BY bucket_start DESC LIMIT 1"
    )

    if not rows:
        raise AssertionError("No all-agents rollup row found (probe_agent_id IS NULL)")

    parts = rows[0].split("|")
    probe_count = int(parts[0])
    p50_ms = int(parts[1])
    print(f"  All-agents rollup: count={probe_count}, p50={p50_ms}ms")
    assert probe_count >= 1, f"Expected probe_count >= 1, got {probe_count}"


def get_tests():
    return [
        test_hourly_aggregates,
        test_all_agents_rollup,
    ]
