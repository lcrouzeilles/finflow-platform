#!/usr/bin/env bash
# Runs one k6 scenario against the stack started with perf/docker-compose.perf.yml.
#
#   perf/run.sh transfers | webhook-duplicates | stripe-outage [extra k6 args]
#
# The token is requested from the host so that its issuer matches the
# gateway's configuration (localhost:8081); k6 then runs inside the compose
# network and calls the gateway and fake-stripe by service name.
set -euo pipefail

scenario="${1:?usage: perf/run.sh <transfers|webhook-duplicates|stripe-outage> [k6 args]}"
shift

root="$(cd "$(dirname "$0")/.." && pwd)"
script="$root/perf/k6/$scenario.js"
[ -f "$script" ] || { echo "unknown scenario: $scenario" >&2; exit 2; }

network="$(basename "$root")_default"
# Docker Desktop on Windows needs a native path for the bind mount.
mount_root="$(cygpath -m "$root" 2>/dev/null || echo "$root")"

token="$(curl -sf -X POST http://localhost:8081/realms/finflow/protocol/openid-connect/token \
  -d grant_type=password -d client_id=finflow-client \
  -d username=demo-user -d password=demo-user \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
[ -n "$token" ] || { echo "could not get a token from Keycloak on localhost:8081" >&2; exit 1; }

# MSYS_NO_PATHCONV keeps Git Bash on Windows from rewriting the container paths.
status=0
MSYS_NO_PATHCONV=1 docker run --rm -i \
  --network "$network" \
  -v "$mount_root/perf/k6:/scripts:ro" \
  -e TOKEN="$token" \
  -e BASE_URL=http://api-gateway:8080 \
  -e FAKE_STRIPE_URL=http://fake-stripe:8080 \
  grafana/k6:0.54.0 run "$@" "/scripts/$scenario.js" || status=$?

if [ "$scenario" = "stripe-outage" ]; then
  # A failed deposit must not leave a row behind (deposits are inserted only
  # after Stripe returned a session).
  zombies="$(docker exec finflow-postgres psql -U finflow -d finflow -Atc \
    "select count(*) from deposits where stripe_session_id is null")"
  echo "deposits without a Stripe session (must be 0): $zombies"
  [ "$zombies" = "0" ] || status=1
fi

exit "$status"
