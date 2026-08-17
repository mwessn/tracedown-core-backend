#!/usr/bin/env bash
# Builds and (re)starts the tracedown-testbin container attached to the
# dev stack's network — the deterministic probe target the demo seeds
# point at.
#
# Usage: ./scripts/start-testbin.sh [port] [--no-build]
#
# port: HTTP port (default: 20780). TLS listens on port+1, the raw-TCP
#       chaos listener on port+2; all three are mapped to the host too.
# Requires: the backend docker network (any `docker compose up` from
# docker/ creates it).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
API_DIR="$(cd "$SCRIPT_DIR/../../tracedown-testbin" && pwd)"

IMAGE="tracedown-testbin"
CONTAINER="tracedown-testbin"
NETWORK="tracedown_tracedown-net"

PORT="${1:-20780}"
if ! [[ "$PORT" =~ ^[0-9]+$ ]]; then
  echo "Usage: $0 [port] [--no-build]" >&2
  exit 1
fi
TLS_PORT=$((PORT + 1))
CHAOS_PORT=$((PORT + 2))

BUILD=1
if [[ "${2:-}" == "--no-build" || "${1:-}" == "--no-build" ]]; then
  BUILD=0
fi

if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
  echo "ERROR: network '$NETWORK' not found — start the backend stack first." >&2
  exit 1
fi

if [[ "$BUILD" -eq 1 ]]; then
  echo "==> Building $IMAGE image..."
  docker build -q -f "$API_DIR/docker/Dockerfile" -t "$IMAGE" "$API_DIR"
fi

if docker inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "==> Removing existing $CONTAINER container..."
  docker rm -f "$CONTAINER" > /dev/null
fi

echo "==> Starting $CONTAINER on $NETWORK (http :$PORT, tls :$TLS_PORT, chaos :$CHAOS_PORT)..."
docker run -d \
  --name "$CONTAINER" \
  --network "$NETWORK" \
  -e PORT="$PORT" \
  -e TLS_PORT="$TLS_PORT" \
  -e CHAOS_PORT="$CHAOS_PORT" \
  -p "$PORT:$PORT" \
  -p "$TLS_PORT:$TLS_PORT" \
  -p "$CHAOS_PORT:$CHAOS_PORT" \
  --restart unless-stopped \
  "$IMAGE" \
  uvicorn main:app --host 0.0.0.0 --port "$PORT" > /dev/null

echo "==> Waiting for health..."
for i in $(seq 1 30); do
  if curl -sf "http://localhost:$PORT/health" > /dev/null 2>&1; then
    echo "==> tracedown-testbin is up:"
    echo "      http   http://localhost:$PORT        (in-network: http://tracedown-testbin:$PORT)"
    echo "      tls    https://localhost:$TLS_PORT       (self-signed)"
    echo "      chaos  tcp://localhost:$CHAOS_PORT         (/rst /empty /partial)"
    echo "==> Seed demo services against it: ./scripts/seeds/seed-demo.sh http://tracedown-testbin:$PORT"
    exit 0
  fi
  sleep 1
done

echo "ERROR: tracedown-testbin did not become healthy in 30s. Logs:" >&2
docker logs "$CONTAINER" --tail 30 >&2
exit 1
