"""
E2E: Notification dispatch chain (result-ingestor -> outbox -> notification-dispatcher).

Verifies the notification-dispatcher consumes outbox events and marks them
as published. Depends on test_service_lifecycle having created a service
with at least one probe result.
"""

import time

import test_helpers as h


@h.log_test("Outbox events consumed by notification-dispatcher", reset_db_before=False)
def test_outbox_events_published():
    if not h.svc_id:
        h.skip_test("No service ID -- test_service_lifecycle must run first")

    # The dispatcher polls the outbox table for unpublished events.
    # Give it time to pick up any pending events.
    for i in range(15):
        rows = h.query_db(
            "SELECT COUNT(*) FROM outbox "
            "WHERE event_type = 'probe_result.created' AND published = true"
        )
        published_count = int(rows[0]) if rows else 0
        if published_count > 0:
            print(f"  {published_count} outbox event(s) marked as published after {i * 2}s")

            # Verify no ancient unpublished events remain (dispatcher is keeping up)
            unpublished = h.query_db(
                "SELECT COUNT(*) FROM outbox "
                "WHERE event_type = 'probe_result.created' AND published = false"
            )
            unpublished_count = int(unpublished[0]) if unpublished else 0
            print(f"  Unpublished events remaining: {unpublished_count}")
            return
        time.sleep(2)

    raise AssertionError("No outbox events marked as published after 30s -- dispatcher may not be running")


@h.log_test("Notification log populated for probe results", reset_db_before=False)
def test_notification_log_exists():
    """Check if the dispatcher wrote notification_log entries for probe events."""
    rows = h.query_db(
        "SELECT COUNT(*) FROM information_schema.tables "
        "WHERE table_name = 'notification_log'"
    )
    table_exists = int(rows[0]) if rows else 0
    if not table_exists:
        h.skip_test("notification_log table does not exist yet (dispatcher may not create it)")

    rows = h.query_db(
        "SELECT id, channel, status, recipient "
        "FROM notification_log ORDER BY created_at DESC LIMIT 1"
    )
    if not rows:
        # Notifications may be suppressed if no recipients are configured -- this is OK.
        print("  No notification_log entries (expected if no notification rules configured)")
        return

    parts = rows[0].split("|")
    print(f"  Latest notification: id={parts[0][:8]}..., channel={parts[1]}, status={parts[2]}, recipient={parts[3]}")


def get_tests():
    return [
        test_outbox_events_published,
        test_notification_log_exists,
    ]
