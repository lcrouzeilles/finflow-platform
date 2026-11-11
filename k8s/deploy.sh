#!/usr/bin/env bash
# Deploys FinFlow to the local Docker Desktop Kubernetes cluster
# (kind provisioner) in the finflow namespace.
#
#   k8s/deploy.sh               build the images and apply everything
#   SKIP_BUILD=1 k8s/deploy.sh  reuse the images already built
#   OVERLAY=perf k8s/deploy.sh  Stripe replaced by WireMock, for perf/run.sh (TARGET=k8s)
#
# Remove everything with: kubectl --context docker-desktop delete namespace finflow
#
# Besides the overlay, it creates what must not or cannot live in it:
#   - the finflow-secrets Secret, from .env (never committed)
#   - ConfigMaps from files shared with Docker Compose (Keycloak realm,
#     Tempo config, alert rules, Grafana provisioning), which kustomize
#     cannot read from outside its directory.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

context="${KUBE_CONTEXT:-docker-desktop}"
namespace=finflow
overlay="${OVERLAY:-local}"
case "$overlay" in local|perf) ;; *) echo "OVERLAY must be local or perf" >&2; exit 2;; esac

k() { kubectl --context "$context" "$@"; }
# kubectl on Windows needs native paths.
native_path() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

[ -f .env ] || { echo ".env not found: cp .env.example .env" >&2; exit 1; }

# Images: built by Docker Compose and tagged finflow/*:local. The cluster
# pulls them through Docker Desktop's registry mirror, which serves the
# local image store, so no registry or image import is needed.
if [ "${SKIP_BUILD:-0}" != 1 ]; then
  docker compose build transaction-service api-gateway
fi

k apply -f k8s/overlays/local/namespace.yaml

# Secret from .env: only the keys the cluster needs, surrounding quotes
# removed. Written to a private temporary file, never echoed.
env_value() {
  local line value
  line="$(grep -E "^$1=" .env | tail -n 1 || true)"
  value="${line#*=}"
  value="${value%$'\r'}"
  value="${value#\"}"; value="${value%\"}"
  value="${value#\'}"; value="${value%\'}"
  printf '%s' "$value"
}
secret_file="$(mktemp)"
trap 'rm -f "$secret_file"' EXIT
chmod 600 "$secret_file"
for key in DB_USERNAME DB_PASSWORD KC_BOOTSTRAP_ADMIN_USERNAME KC_BOOTSTRAP_ADMIN_PASSWORD \
           STRIPE_SECRET_KEY STRIPE_WEBHOOK_SECRET; do
  value="$(env_value "$key")"
  # Throwaway credentials that only work against fake-stripe, as in
  # perf/docker-compose.perf.yml.
  if [ "$overlay" = perf ]; then
    case "$key" in
      STRIPE_SECRET_KEY) value=sk_test_perf_only ;;
      STRIPE_WEBHOOK_SECRET) value=whsec_perf_only ;;
    esac
  fi
  printf '%s=%s\n' "$key" "$value" >> "$secret_file"
done
k -n "$namespace" create secret generic finflow-secrets \
  --from-env-file="$(native_path "$secret_file")" --dry-run=client -o yaml | k apply -f -

configmap() {
  local name="$1"; shift
  k -n "$namespace" create configmap "$name" "$@" --dry-run=client -o yaml | k apply -f -
}
configmap keycloak-realm --from-file=keycloak/finflow-realm.json
configmap tempo-config --from-file=observability/tempo/tempo.yaml
configmap prometheus-rules --from-file=observability/prometheus/alerts.yml
configmap grafana-datasources --from-file=observability/grafana/provisioning/datasources/datasources.yaml
configmap grafana-dashboards \
  --from-file=observability/grafana/provisioning/dashboards/dashboards.yaml \
  --from-file=observability/grafana/provisioning/dashboards/finflow.json
if [ "$overlay" = perf ]; then
  configmap fake-stripe-mappings --from-file=perf/wiremock/mappings
else
  # Back from the perf overlay: apply does not prune what it no longer lists.
  k -n "$namespace" delete deployment/fake-stripe service/fake-stripe service/fake-stripe-external \
    configmap/fake-stripe-mappings --ignore-not-found
fi

# The image tag does not change between builds, so running application
# pods are restarted to pull the images just built.
redeploy=0
k -n "$namespace" get deployment transaction-service >/dev/null 2>&1 && redeploy=1
k apply -k "k8s/overlays/$overlay"
if [ "$redeploy" = 1 ]; then
  k -n "$namespace" rollout restart deployment/transaction-service deployment/api-gateway
fi

for workload in statefulset/postgres statefulset/kafka deployment/redis deployment/keycloak \
                deployment/tempo deployment/prometheus deployment/grafana \
                deployment/transaction-service deployment/api-gateway; do
  k -n "$namespace" rollout status "$workload" --timeout=5m
done
if [ "$overlay" = perf ]; then
  k -n "$namespace" rollout status deployment/fake-stripe --timeout=5m
fi

cat <<EOF

FinFlow is running in namespace $namespace (overlay $overlay):
  API gateway  http://localhost:8180
  Keycloak     http://localhost:8181  (tokens for the gateway come from here)
  Grafana      kubectl --context $context -n $namespace port-forward svc/grafana 3000:3000
  Prometheus   kubectl --context $context -n $namespace port-forward svc/prometheus 9090:9090
EOF
