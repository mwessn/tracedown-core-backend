"""
E2E test runner. Discovers and runs all test modules in order.

Each test module exports a `get_tests()` function returning an ordered
list of test functions decorated with `@h.log_test()`.
"""

import importlib
import os
import sys
import time

import test_helpers as h

# Test modules in execution order. Add new modules here.
TEST_MODULES = [
    "test_service_lifecycle",
    "test_result_ingestor",
    "test_run_now",
    "test_usage",
    "test_variables",
    "test_webhook_delivery",
    "test_health_challenge",
    "test_agent_renew",
    "test_aggregation",
    "test_email_dispatch",
    "test_api_keys",
    "test_permissions",
    "test_domains",
    "test_notification_flow",
    "test_metrics_scrape",
]


def wait_for_gateway(timeout=60):
    """Wait for the gateway to respond to /ping."""
    for i in range(timeout):
        try:
            status, _ = h.api("GET", "/ping")
            if status == 200:
                return True
        except Exception:
            pass
        time.sleep(1)
    return False


def main():
    print("=" * 60)
    print("TRACEDOWN END-TO-END TESTS")
    print(f"Gateway: {h.GATEWAY_URL}")
    print("=" * 60)

    if not wait_for_gateway():
        print("ERROR: Gateway not reachable after 60s")
        sys.exit(1)

    # Load and run test modules
    for module_name in TEST_MODULES:
        mod = importlib.import_module(module_name)
        tests = mod.get_tests()
        for test_fn in tests:
            test_fn()

    print(f"\n{'=' * 60}")
    print(f"Results: {h.tests_passed} passed, {h.tests_failed} failed, {h.tests_skipped} skipped")
    print(f"{'=' * 60}")

    sys.exit(1 if h.tests_failed > 0 else 0)


if __name__ == "__main__":
    main()
