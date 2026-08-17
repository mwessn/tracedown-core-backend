"""
E2E: Result ingestor persistence.

Verifies the result-ingestor correctly consumes from the Redis queue
and persists results, steps, service status updates, and outbox events.
Depends on test_service_lifecycle having run first (uses h.svc_id).
"""

import json
import time

import test_helpers as h


@h.log_test("Result persisted in probe_results table", reset_db_before=False)
def test_result_persisted():
    rows = h.query_db(
        f"SELECT id, status, run_duration_ms, raw_result::text "
        f"FROM probe_results WHERE service_id = '{h.svc_id}' "
        f"ORDER BY started_at DESC LIMIT 1"
    )
    assert rows, "No probe_results row found"
    parts = rows[0].split("|", 3)
    result_id = parts[0]
    status = parts[1]
    duration = parts[2]
    raw = json.loads(parts[3])
    print(f"  id={result_id[:8]}..., status={status}, duration={duration}ms")
    print(f"  raw_result.outcome={raw.get('outcome')}, calls={len(raw.get('calls', []))}")
    assert status == "success", f"Expected success, got {status}"
    assert int(duration) > 0, f"Expected duration > 0, got {duration}"
    assert raw.get("outcome") == "success"
    assert len(raw.get("calls", [])) >= 1


@h.log_test("Service last_status and last_run_id updated", reset_db_before=False)
def test_service_status_updated():
    rows = h.query_db(
        f"SELECT last_status, last_run_id, last_status_since, last_status_consecutive "
        f"FROM services WHERE id = '{h.svc_id}'"
    )
    assert rows, "Service not found"
    parts = rows[0].split("|")
    last_status = parts[0]
    last_run_id = parts[1]
    last_status_since = parts[2]
    consecutive = parts[3]
    print(f"  last_status={last_status}")
    print(f"  last_run_id={last_run_id[:8]}...")
    print(f"  last_status_since={last_status_since}")
    print(f"  consecutive={consecutive}")
    assert last_status == "success", f"Expected success, got {last_status}"
    assert last_run_id and last_run_id != "", "last_run_id should be set"
    assert last_status_since and last_status_since != "", "last_status_since should be set"
    assert int(consecutive) >= 1, f"Expected consecutive >= 1, got {consecutive}"

    # Verify last_run_id points to an actual probe_results row
    verify = h.query_db(
        f"SELECT id FROM probe_results WHERE id = '{last_run_id}'"
    )
    assert verify, f"last_run_id {last_run_id} does not exist in probe_results"


@h.log_test("Probe steps persisted with correct data", reset_db_before=False)
def test_probe_steps():
    rows = h.query_db(
        f"SELECT ps.step_num, ps.request_url, ps.status_code, "
        f"ps.response_time_ms, ps.dns_ms, ps.assertion_results::text "
        f"FROM probe_steps ps "
        f"JOIN probe_results pr ON ps.probe_result_id = pr.id "
        f"WHERE pr.service_id = '{h.svc_id}' "
        f"ORDER BY pr.started_at DESC, ps.step_num LIMIT 5"
    )
    assert rows, "No probe_steps found"
    for row in rows:
        parts = row.split("|", 5)
        step_num = parts[0]
        url = parts[1]
        status_code = parts[2]
        response_time = parts[3]
        dns_ms = parts[4]
        assertions_raw = parts[5] if len(parts) > 5 else ""
        print(f"  Step {step_num}: {url}")
        print(f"    status_code={status_code}, response_time={response_time}ms, dns={dns_ms}ms")

        assert url.startswith("http"), f"Expected URL, got {url}"
        assert status_code == "200", f"Expected 200, got {status_code}"
        assert int(response_time) > 0, f"Expected response_time > 0"

        # Verify assertions were stored
        if assertions_raw and assertions_raw != "":
            assertions = json.loads(assertions_raw)
            print(f"    assertions={len(assertions)} items")
            assert len(assertions) >= 1, "Expected at least 1 assertion"
            # Verify the status assertion passed
            status_assertion = next(
                (a for a in assertions if a.get("scope") == "status"), None
            )
            if status_assertion:
                print(f"    status assertion: {status_assertion.get('outcome')}")
                assert status_assertion.get("outcome") == "passed"


@h.log_test("Outbox event created for notification pipeline", reset_db_before=False)
def test_outbox_event():
    rows = h.query_db(
        f"SELECT id, event_type, aggregate_type, payload::text, published "
        f"FROM outbox WHERE event_type = 'probe_result.created' "
        f"ORDER BY created_at DESC LIMIT 1"
    )
    assert rows, "No outbox event found"
    parts = rows[0].split("|", 4)
    event_id = parts[0]
    event_type = parts[1]
    aggregate_type = parts[2]
    payload = json.loads(parts[3])
    published = parts[4]
    print(f"  id={event_id[:8]}...")
    print(f"  event_type={event_type}, aggregate_type={aggregate_type}")
    print(f"  published={published}")
    print(f"  payload.status={payload.get('status')}, serviceId={payload.get('serviceId', '')[:8]}...")

    assert event_type == "probe_result.created"
    assert aggregate_type == "probe_result"
    # published may be 't' (dispatcher consumed it) or 'f' (not yet consumed)
    assert published in ("t", "f"), f"Expected boolean, got {published}"
    assert payload.get("serviceId") == h.svc_id
    assert payload.get("status") == "success"
    assert payload.get("runDurationMs") is not None


@h.log_test("Redis queue drained by ingestor", reset_db_before=False)
def test_queue_drained():
    # After the ingestor processes results, the queue should be empty
    # (give it a moment to consume)
    time.sleep(3)
    length = h.query_redis("LLEN probe_results_queue")
    count = int(length) if length and length != "(nil)" else 0
    print(f"  Queue length: {count}")
    assert count == 0, f"Expected empty queue, got {count} items (ingestor not consuming?)"


def get_tests():
    return [
        test_result_persisted,
        test_service_status_updated,
        test_probe_steps,
        test_outbox_event,
        test_queue_drained,
    ]
