#!/usr/bin/env bash
# Runs one k6 scenario against the Docker Compose stack started with
# perf/docker-compose.perf.yml, or against the local Kubernetes cluster
# deployed with OVERLAY=perf k8s/deploy.sh.
#
#   perf/run.sh transfers | webhook-duplicates | stripe-outage [extra k6 args]
#   TARGET=k8s perf/run.sh <scenario> [extra k6 args]
#
# The token is requested from the host so that its issuer matches the
# gateway's configuration (localhost:8081 for Compose, localhost:8181 for
# the cluster). With Compose, k6 runs inside the compose network and calls
# the gateway and fake-stripe by service name; with the cluster, it calls
# the ports the cluster publishes on the host.
set -euo pipefail

scenario="${1:?usage: [TARGET=compose|k8s] perf/run.sh <transfers|webhook-duplicates|stripe-outage> [k6 args]}"
shift

root="$(cd "$(dirname "$0")/.." && pwd)"
script="$root/perf/k6/$scenario.js"
[ -f "$script" ] || { echo "unknown scenario: $scenario" >&2; exit 2; }

target="${TARGET:-compose}"
case "$target" in
  compose)
    keycloak_url=http://localhost:8081
    network_args=(--network "$(basename "$root")_default")
    base_url=http://api-gateway:8080
    fake_stripe_url=http://fake-stripe:8080
    ;;
  k8s)
    keycloak_url=http://localhost:8181
    network_args=()
    base_url=http://host.docker.internal:8180
    fake_stripe_url=http://host.docker.internal:8182
    ;;
  *) echo "TARGET must be compose or k8s" >&2; exit 2 ;;
esac

# Docker Desktop on Windows needs a native path for the bind mount.
mount_root="$(cygpath -m "$root" 2>/dev/null || echo "$root")"

token="$(curl -sf -X POST "$keycloak_url/realms/finflow/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=finflow-client \
  -d username=demo-user -d password=demo-user \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
[ -n "$token" ] || { echo "could not get a token from Keycloak on $keycloak_url" >&2; exit 1; }

# MSYS_NO_PATHCONV keeps Git Bash on Windows from rewriting the container paths.
status=0
MSYS_NO_PATHCONV=1 docker run --rm -i \
  "${network_args[@]}" \
  -v "$mount_root/perf/k6:/scripts:ro" \
  -e TOKEN="$token" \
  -e BASE_URL="$base_url" \
  -e FAKE_STRIPE_URL="$fake_stripe_url" \
  grafana/k6:0.54.0 run "$@" "/scripts/$scenario.js" || status=$?

if [ "$scenario" = "stripe-outage" ]; then
  # A failed deposit must not leave a row behind (deposits are inserted only
  # after Stripe returned a session).
  query="select count(*) from deposits where stripe_session_id is null"
  if [ "$target" = k8s ]; then
    zombies="$(kubectl --context "${KUBE_CONTEXT:-docker-desktop}" -n finflow exec postgres-0 -- \
      sh -c "psql -U \"\$POSTGRES_USER\" -d finflow -Atc '$query'")"
  else
    zombies="$(docker exec finflow-postgres psql -U finflow -d finflow -Atc "$query")"
  fi
  zombies="${zombies%$'\r'}"
  echo "deposits without a Stripe session (must be 0): $zombies"
  [ "$zombies" = "0" ] || status=1
fi

exit "$status"
