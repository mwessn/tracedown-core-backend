#!/usr/bin/env bash
# Seeds the Demo project with 18 services against tracedown-testbin, each
# exercising a distinct Tracedown platform capability — see demo-services.sql
# for the map.
#
# Usage: ./scripts/seeds/seed-demo.sh [API_URL] [CHAOS_URL]
#   API_URL    base URL of tracedown-testbin as reachable FROM THE AGENT
#              (default: the public instance, https://testbin.tracedown.dev)
#   CHAOS_URL  target for the connection-error demo (default: an unresolvable
#              host, so the probe fails fast with a genuine connection error)
#
# The default targets the live public testbin so a fresh stack has working
# demo probes with no local testbin container. To point at a local testbin
# instead, pass it explicitly, e.g.:
#   ./seed-demo.sh http://tracedown-testbin:20780 http://tracedown-testbin:20782
# The public instance does NOT expose the raw-TCP chaos listener (Railway only
# routes the HTTP port), and a black-holed port would hang the probe past the
# scheduler's dispatch window — so the connection-error demo defaults to an
# unresolvable host (fast DNS failure = the same connection-error outcome). A
# local testbin runs the real chaos listener; pass its :20782 URL to use it.
#
# Requires: tracedown-postgres container running, org bootstrapped.

set -euo pipefail
cd "$(dirname "$0")"

API_URL="${1:-https://testbin.tracedown.dev}"
CHAOS_URL="${2:-http://unreachable.invalid}"

echo "==> Seeding demo services (api: $API_URL, chaos: $CHAOS_URL)..."
sed -e "s#__API_URL__#${API_URL}#g" -e "s#__CHAOS_URL__#${CHAOS_URL}#g" demo-services.sql \
  | docker exec -i tracedown-postgres psql -U tracedown -d tracedown

echo "==> Publishing schedule nudges so scheduler picks up new services..."
docker exec tracedown-redis-a redis-cli PUBLISH schedule:nudge "seed" > /dev/null 2>&1 || true

echo "==> Done. Services will start probing on the next cron tick."
