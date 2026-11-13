# FinFlow Transaction Platform

[![CI](https://github.com/lcrouzeilles/finflow-platform/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/lcrouzeilles/finflow-platform/actions/workflows/ci.yml)

FinFlow is a production-oriented backend platform for processing financial transactions, built as a portfolio and learning project.

The project focuses on modern backend engineering, security, distributed systems, and cloud-native infrastructure.

## Architecture

```text
Client ──JWT──► API Gateway ──► Transaction Service ──► PostgreSQL
                     ▲             │        │              (accounts, transactions, deposits,
                     │             │        │               outbox_events, processed_webhook_events,
                     │             │        │               reconciliation_discrepancies)
                     │             │        └──► Redis (account cache, graceful degradation)
                     │             │
                     │             ├── Outbox publisher ──► Kafka (finflow.transfer.completed,
                     │             │                               finflow.deposit.completed)
                     │             │
                     │             └── Stripe client (retry + circuit breaker) ──► Stripe (test mode)
                     │                                                               │
Stripe ──signed webhook──► /webhooks/stripe ─────────────────────────────────────────┘

Keycloak ──► issues the JWTs validated by the gateway and the service
```

The API Gateway is the public entry point. The Transaction Service runs internally and is not directly exposed when using Docker.

Every state change and its event are committed in the same database transaction (transactional outbox); a background publisher relays the events to Kafka with at-least-once semantics.

Money enters the system only through Stripe Checkout: the service never sees card data, and a balance is credited only by a signature-verified, deduplicated webhook (see [Payments](#payments)).

## Repository

```text
finflow-transaction-platform/
├── api-gateway/
├── transaction-service/
├── keycloak/
├── observability/        (Tempo, Prometheus + alert rules, Alertmanager routes, Grafana provisioning)
├── perf/                 (k6 load and resilience scenarios)
├── k8s/                  (Kustomize manifests and deploy script for a local cluster)
├── docker-compose.yml
└── README.md
```

The project uses a single Git repository with independently deployable Spring Boot applications.

## Technology Stack

- Java 21
- Spring Boot
- Spring Cloud Gateway
- Spring Security
- Keycloak / OAuth 2.0 / OpenID Connect
- PostgreSQL
- Spring Data JPA / Hibernate
- Flyway
- Redis (Spring Cache)
- Apache Kafka (Spring for Apache Kafka)
- Stripe (Checkout Sessions and webhooks, test mode) with Resilience4j
- OpenAPI / Swagger
- Micrometer, OpenTelemetry (OTLP), Grafana Tempo, Prometheus, Grafana
- Docker / Docker Compose
- Kubernetes (Kustomize)
- Testcontainers
- Maven

## Current Features

- REST API for accounts and transfers
- Transaction management and rollback
- Pessimistic row locking with deterministic lock ordering for concurrent transfers
- Idempotency support (replay returns the original transaction; concurrent duplicates resolved safely)
- Account-level authorization: the owner is derived from the JWT, never from the request body
- Transactional outbox with lease-based claiming, exponential backoff, retry limit and metrics
- Kafka publication of transfer and deposit events, with a consumer that ignores redeliveries
- Deposits through Stripe Checkout: hosted payment page, idempotent creation, owner-scoped
- Signature-authenticated Stripe webhooks processed exactly once (dedup and credit in one transaction)
- Retry, circuit breaker and timeouts around the Stripe client (503 contract when the provider is down)
- Report-only reconciliation job between Stripe and the ledger
- Redis account cache with graceful degradation
- PostgreSQL persistence
- Flyway database migrations
- JWT authentication and role-based authorization
- Keycloak identity management
- API Gateway routing and security
- Dockerized local environment
- Kubernetes deployment (local cluster) with probes, pod security and two service replicas
- Integration testing with Testcontainers
- OpenAPI documentation

## Docker Environment

Docker Compose runs the whole platform on one host; see [Kubernetes](#kubernetes-local-cluster) for the cluster deployment.

| Service | Host Port | Container Port |
|---|---:|---:|
| API Gateway | `${GATEWAY_PORT}` (default `8080`) | `8080`, `9091` (actuator, not published) |
| Keycloak | `8081` | `8080` |
| PostgreSQL | `55433` | `5432` |
| Redis | `6379` | `6379` |
| Kafka (external listener) | `9094` | `9094` |
| Transaction Service | — | `8080` |
| Grafana (localhost only) | `${GRAFANA_PORT}` (default `3000`) | `3000` |
| Prometheus (localhost only) | `${PROMETHEUS_PORT}` (default `9090`) | `9090` |
| Alertmanager (localhost only) | `${ALERTMANAGER_PORT}` (default `9093`) | `9093` |
| alert-log (notification sink) | — | `8080` |
| Tempo | — | `3200`, `4318` (OTLP/HTTP) |

The Transaction Service is accessible internally through:

```text
http://transaction-service:8080
```

## Configuration

Local secrets live in a git-ignored `.env` file read by Docker Compose. Create it from the template and adjust the values:

```bash
cp .env.example .env
```

| Variable | Used by |
|---|---|
| `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL and the Transaction Service |
| `KC_BOOTSTRAP_ADMIN_USERNAME`, `KC_BOOTSTRAP_ADMIN_PASSWORD` | Keycloak admin console |
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` | Transaction Service (Stripe test mode: `sk_test_...` and `whsec_...`) |
| `GATEWAY_PORT` | Host port of the API Gateway (default `8080`); change it if that port is taken. Stripe Checkout redirects follow it |
| `GRAFANA_PORT`, `PROMETHEUS_PORT`, `ALERTMANAGER_PORT` | Host ports of Grafana (default `3000`), Prometheus (default `9090`) and Alertmanager (default `9093`), bound to `127.0.0.1` |

`keycloak/finflow-realm.json` is imported on the first start of Keycloak and contains only generic demo identities, no real users:

| Username | Password | Realm roles |
|---|---|---|
| `demo-user` | `demo-user` | `USER` |
| `demo-admin` | `demo-admin` | `USER`, `ADMIN` |

Keycloak does not resolve environment placeholders inside imported user credentials, so these demo passwords are literal; they are for the local environment only. The realm is imported once: after changing the file, recreate the Keycloak volume (`docker compose down -v` removes all local data) for the changes to apply. The `finflow-client` public client has Direct Access Grants enabled (password grant) to simplify local testing; this is a development convenience and should be disabled outside local environments.

## Running

Start the complete platform from the repository root:

```bash
docker compose up --build
```

Check the services:

```bash
docker compose ps
```

API Gateway (replace `8080` with `GATEWAY_PORT` if you changed it):

```text
http://localhost:8080
```

Keycloak:

```text
http://localhost:8081
```

Swagger UI:

```text
http://localhost:8080/swagger-ui/index.html
```

### Kubernetes (local cluster)

The same platform runs on Docker Desktop's built-in Kubernetes (Settings → Kubernetes → Enable). Manifests use Kustomize:

| Path | Contents |
|---|---|
| `k8s/base` | The gateway and the transaction service: probes on the actuator health groups, resource requests and memory limits, `restricted` pod security (non-root UID 10001, read-only root filesystem, no capabilities, `RuntimeDefault` seccomp), rolling updates with no unavailable pod, two service replicas and a PodDisruptionBudget |
| `k8s/infra` | Development stand-ins for PostgreSQL, Redis, Kafka (single KRaft node) and Keycloak; managed services replace them in the cloud |
| `k8s/observability` | Tempo, Prometheus (discovers every service and gateway pod, shares the alert rules with Compose), Alertmanager with its `alert-log` sink, and Grafana |
| `k8s/overlays/local` | Everything in the `finflow` namespace; the gateway and Keycloak published on `localhost:8180` and `localhost:8181`, so the Compose stack can keep running |
| `k8s/overlays/perf` | The local overlay with WireMock in place of Stripe, for the k6 scenarios |

```bash
k8s/deploy.sh                 # build images, create the Secret and ConfigMaps, apply, wait for every rollout
kubectl --context docker-desktop -n finflow get pods
kubectl --context docker-desktop -n finflow port-forward svc/grafana 3000:3000
kubectl --context docker-desktop -n finflow port-forward svc/alertmanager 9093:9093
kubectl --context docker-desktop -n finflow logs deployment/alert-log   # notifications sent
kubectl --context docker-desktop delete namespace finflow   # remove everything
```

`deploy.sh` builds the images through Docker Compose (`finflow/*:local`); the cluster pulls them through Docker Desktop's registry mirror, so no registry is involved. It creates the `finflow-secrets` Secret from `.env`, so no credential lives in the manifests, and the ConfigMaps for the files shared with Compose (Keycloak realm, Tempo config, alert rules, Grafana provisioning). Tokens come from `http://localhost:8181`, which is the issuer the local overlay configures; the services read the keys from `keycloak:8080` inside the cluster.

The probes are `/actuator/health/liveness` and `/actuator/health/readiness`, open without a token. The service's readiness includes the database, so a pod that cannot reach it leaves the Service instead of failing requests, while liveness ignores it so a database outage never restarts pods. The first service pods may restart once while PostgreSQL is still starting.

CI renders every overlay and validates it against the Kubernetes API schemas with kubeconform.

If `kubectl` fails with `x509: certificate signed by unknown authority` on Windows, an antivirus is probably intercepting HTTPS to the local API server: add an exception for `https://127.0.0.1:<port>` (see `kubectl config view`) rather than skipping TLS verification.

### Stripe webhooks (local smoke test)

Deposits are confirmed only by Stripe webhooks, so a local run needs the
[Stripe CLI](https://docs.stripe.com/stripe-cli) forwarding events to the
gateway. CI never talks to Stripe; this is a manual check.

```bash
stripe login                                   # test mode account
stripe listen --forward-to localhost:8080/webhooks/stripe   # or your GATEWAY_PORT
```

`stripe listen` prints the signing secret (`whsec_...`): put it in `.env` as
`STRIPE_WEBHOOK_SECRET` together with your `sk_test_...` key, then restart the
service. Create a deposit with `POST /deposits`, open the returned
`checkoutUrl` and pay with the test card `4242 4242 4242 4242`; the
`checkout.session.completed` event credits the account and
`GET /deposits/{id}` turns `COMPLETED`. To exercise the endpoint without
paying, `stripe trigger checkout.session.completed` sends a signed event for a
session this service never created, which is acknowledged and recorded as an
`UNKNOWN_SESSION` discrepancy.

## Security Model

- Authentication is delegated to Keycloak. The gateway and the service both validate the JWT; realm roles become `ROLE_USER` / `ROLE_ADMIN`.
- Identity mapping: the `sub` claim of the token is bound to an application `Owner` (`owners.keycloak_subject`, unique). An owner is provisioned automatically on the first authenticated request.
- Ownership is enforced in the service layer and derived from the token only:
  - `POST /accounts` creates the account for the authenticated owner; any `ownerId` in the body is ignored.
  - `GET /accounts/{id}` returns `404` for accounts of other owners (the same response as for a nonexistent ID, so account IDs cannot be probed).
  - `POST /transfers` requires the source account to belong to the caller (`403` otherwise). Transfers to accounts of other owners are allowed.
  - An idempotency key can only be replayed by the owner who created the transaction (`403` otherwise), so a foreign key cannot be used to read someone else's transaction.
  - `POST /deposits` only funds accounts of the caller (`404` otherwise, like `GET /accounts/{id}`); `GET /deposits/{id}` hides deposits of other owners the same way, and a deposit idempotency key can only be replayed by its owner (`403`).
- `POST /webhooks/stripe` carries no JWT. Its authentication is the `Stripe-Signature` header (HMAC over the exact bytes received, 5-minute tolerance); the gateway and the service permit the route and the service rejects anything unsigned with `400` before touching the database.

## Error Contract

All errors share the same JSON body (`timestamp`, `status`, `error`, `message`, `path`, `fieldErrors`):

| Status | When |
|---:|---|
| `400` | Bean validation failures (per-field messages in `fieldErrors`), malformed JSON, missing `Idempotency-Key` header, invalid key length, invalid or missing Stripe webhook signature |
| `401` | Missing or invalid token |
| `403` | Source account, replayed transaction or replayed deposit not owned by the caller |
| `404` | Unknown account / owner / deposit, or one the caller does not own |
| `409` | Same `Idempotency-Key` with different transfer or deposit data, or an unresolved uniqueness conflict |
| `422` | Business-rule violations: insufficient funds, currency mismatch, same source and destination, unsupported deposit currency, amount below the minimum or with more than 2 decimals, inactive account |
| `500` | Unexpected failure; the message is generic and the cause is only logged |
| `502` | The payment provider rejected the request (non-retryable error); the provider's message is only logged |
| `503` | The payment provider is unreachable after the retry policy, or its circuit breaker is open: `Payment provider unavailable, retry later`. No deposit was recorded; retry with the same `Idempotency-Key` |

Amounts accept at most 15 integer digits and 2 decimals.

## Transactional Outbox and Delivery Guarantees

- The transfer, the ledger row and the `outbox_events` row are written in one database transaction.
- A scheduled publisher claims pending events with `FOR UPDATE SKIP LOCKED` and a lease (`claim_until`), publishes them to Kafka (keyed by `sourceAccountId`, synchronous ack) and marks them published.
- Failures are retried with exponential backoff (1s, 2s, ... capped at 300s). After `finflow.outbox.max-attempts` (default `10`) failed attempts the event is marked terminally failed (`failed_at`) and stops being retried; it stays in the table for inspection and manual repair.
- Operators (role `ADMIN`, through the gateway) list the exhausted events with `GET /admin/outbox/failed?limit=50` (oldest failure first, without the payload) and, once the cause is fixed, requeue one with `POST /admin/outbox/{eventId}/retry`: the event gets a fresh retry budget and the publisher picks it up on its next poll. Retrying an event that is pending or published returns `409`; each retry is logged with the operator's subject.
- A failure on one event, including a failure to record its status after the lease expired, never aborts the rest of the batch.
- Delivery is **at least once**: an event delivered to Kafka whose status update fails is delivered again. Consumers must deduplicate on `eventId`; the payload also carries `transactionId` for correlation.
- The transfer consumer does: it claims `(consumer, eventId)` in `processed_kafka_events` with `INSERT ... ON CONFLICT DO NOTHING` as the first statement of its transaction, so a redelivered event (even one delivered concurrently during a rebalance) is acknowledged without being applied again, and a failed effect rolls the claim back so the retry applies it. Claims older than `finflow.kafka.dedup.retention` (default 14 days, beyond Kafka's default retention) are purged daily.
- Metrics: the counter `finflow.outbox.events` is tagged with `result=published|failed|exhausted|status_update_failed`; the gauges `finflow.outbox.pending`, `finflow.outbox.oldest.pending.age` and `finflow.outbox.failed` are read from the database on each scrape, so they cover every publisher instance and survive restarts; `finflow.outbox.publish` times each publication by event type.

## Observability

**Correlation id.** Every request gets an `X-Correlation-Id`. The gateway keeps a well-formed incoming value (up to 64 characters from `[A-Za-z0-9-]`) or generates a UUID, forwards it to the transaction service and returns it in the response. The id follows a transfer end to end:

```text
gateway → transaction service (MDC) → outbox_events.correlation_id → Kafka header X-Correlation-Id → consumer (MDC)
```

Webhooks and requests rejected by security get an id as well. Events created outside a request (for example by the reconciliation job) have none.

**Logs.** Every log line includes the id and the current trace and span ids: as a `[correlation-id] [traceId,spanId]` prefix when running locally, and as the `correlationId`, `traceId` and `spanId` fields in the JSON (ECS) logs the `docker` profile writes. Transfers, account creation, deposits and outbox failures are logged at `INFO`/`WARN`, with ids rather than personal data.

**Traces.** Both applications record Micrometer observations through the OpenTelemetry bridge and, in the `docker` profile, export them over OTLP/HTTP to Grafana Tempo (every request is sampled there; production would keep the 10% default or less). Without an endpoint nothing is exported, so tests and plain local runs are unaffected. The W3C `traceparent` header carries the trace from the gateway to the service and onto Kafka records. The outbox is drained by a scheduled job, which would cut the trace at the database, so each `outbox_events` row stores the `traceparent` of the request that wrote it and the publisher continues that trace. A transfer is therefore a single trace:

```text
gateway  http post /transfers/**
└─ transaction-service  http post /transfers  (+ Redis cache evictions)
   └─ outbox publish        (scheduled, parent restored from outbox_events.trace_parent)
      └─ finflow.transfer.completed send     (Kafka producer)
         └─ finflow.transfer.completed process  (Kafka listener)
```

Inside the service, database and Stripe work shows up too. Every JDBC connection and query is a span (`datasource-micrometer`; parameter values are never recorded, result-set fetches are left out), and the `connection` span starts when the pool is asked for a connection, so waiting for one is visible. Each Stripe call is a `stripe <operation>` span with one `stripe attempt` child per HTTP attempt: retries appear as siblings, and a call rejected by the open circuit has no attempt at all.

These spans exposed that open-in-view (Spring Boot's default) kept the request's database connection, acquired by the deposit's idempotency lookup, for the whole request: one `connection` span of 1129 ms enclosed a 432 ms Stripe call, so a slow Stripe could drain the pool and stall transfers too. It is now disabled (`spring.jpa.open-in-view=false`; no entity has lazy associations). In the same scenario a deposit shows several `connection` spans of 2 to 17 ms and none around the Stripe call; `DepositRequestConnectionIntegrationTest` keeps it that way.

Open Grafana → Explore → Tempo and search by service, or paste a `traceId` from a log line.

**Metrics.** `/actuator/prometheus` exposes the Micrometer registry in Prometheus format: the outbox counters and backlog gauges, the Stripe circuit breaker and retry metrics (`resilience4j_*`), the reconciliation discrepancy counter, plus JVM, HTTP (with histogram buckets, for percentiles), Kafka and connection-pool metrics. It needs no token so a scraper can reach it, which is safe here because the gateway does not route `/actuator/**` and Docker Compose does not publish the transaction service's port. `/actuator/metrics` still requires a JWT.

```bash
docker compose exec transaction-service curl -s localhost:8080/actuator/prometheus | grep finflow_outbox
```

**Alerts.** Prometheus scrapes the service and the gateway every 10s and evaluates [`observability/prometheus/alerts.yml`](observability/prometheus/alerts.yml):

| Alert | Fires when | Severity |
|---|---|---|
| `TransactionServiceDown` | the service cannot be scraped for 1 minute | critical |
| `ApiGatewayDown` | the gateway cannot be scraped for 1 minute | critical |
| `StripeCircuitBreakerOpen` | a Stripe circuit breaker was open at any point in the last minute | critical |
| `OutboxNotDraining` | the oldest pending outbox event is older than 2 minutes, for 1 minute | critical |
| `OutboxEventsExhausted` | any outbox event exhausted its retries and needs an operator | warning |
| `HighServerErrorRate` | more than 5% of API requests return 5xx, for 5 minutes | critical |
| `HighLatency` | API p95 latency is above 500ms, for 5 minutes | warning |

The rules have unit tests ([`alerts.test.yml`](observability/prometheus/alerts.test.yml)):

```bash
docker run --rm --entrypoint promtool -v "$PWD/observability/prometheus:/rules" -w /rules prom/prometheus:v3.5.0 test rules alerts.test.yml
```

Firing alerts show up in Prometheus (`/alerts`) and on the dashboard, and Prometheus sends them to Alertmanager ([`observability/alertmanager/alertmanager.yml`](observability/alertmanager/alertmanager.yml)). It groups them by alert name and routes them by severity: `critical` goes to the `on-call` receiver and repeats every hour, everything else to `team-channel` every 4 hours, with a notification when they resolve. Locally both receivers are webhooks to `alert-log`, an echo server that prints each notification (`docker logs finflow-alert-log`); in a real deployment they become PagerDuty and Slack receivers with credentials read from files. CI checks the configuration and the routing with `amtool`. Stopping `transaction-service` delivered `TransactionServiceDown` to `on-call` about 90 seconds later (one minute of `for` plus scrape and group wait), and the resolved notification about 50 seconds after it came back.

During the `stripe-outage` scenario (see [Performance and Resilience Testing](#performance-and-resilience-testing)) `StripeCircuitBreakerOpen` fired about 15 seconds after the outage began, and `HighServerErrorRate` and `HighLatency` went pending while their 5-minute windows were not met.

**Dashboard.** Grafana (`http://localhost:3000`, anonymous admin, bound to localhost) is provisioned with Prometheus and Tempo data sources and the *FinFlow transaction service* dashboard: firing alerts, circuit breaker states, outbox backlog, request rate by status, 5xx ratio, p95 latency per endpoint, Stripe calls, outbox publication results, and the gateway's request rate and end-to-end p95.

**Gateway metrics.** The gateway's port is public, so in containers its actuator (probes and `/actuator/prometheus`) listens on a separate management port, `9091`, which Docker Compose does not publish and the Kubernetes Service does not expose; the probes use it too.

## Payments

Deposits are funded with Stripe Checkout in test mode. The flow has three legs, and only the second one moves money in the ledger:

```text
1. POST /deposits (JWT, Idempotency-Key)
   validate ──► Stripe: create Checkout Session (idempotency key forwarded) ──► INSERT deposits PENDING
   201 { depositId, checkoutUrl }          the row exists only once the session does

2. Customer pays on Stripe's hosted page ──► Stripe ──signed──► POST /webhooks/stripe
   verify Stripe-Signature ──► BEGIN
                               INSERT processed_webhook_events (event_id) ON CONFLICT DO NOTHING  ── 0 rows: duplicate, 200
                               SELECT deposits ... FOR UPDATE ──► SELECT accounts ... FOR UPDATE
                               balance += amount; INSERT transactions (type DEPOSIT); deposit COMPLETED
                               INSERT outbox_events (DEPOSIT_COMPLETED)
                             COMMIT ──► evict account cache ──► 200

3. Outbox publisher ──► Kafka finflow.deposit.completed (at least once, eventId for dedup)
```

**Golden rule.** The success redirect never credits anything; it is user experience, not truth. `GET /deposits/{id}` turns `COMPLETED` only after step 2. Deposits are `PENDING`, `COMPLETED`, `EXPIRED` (the session timed out unpaid) or `FAILED`.

**PCI scope.** Card data is entered on Stripe's hosted page and never reaches this service (no card fields, no PAN, no tokens in logs or the database). That keeps the platform in SAQ-A territory: the service handles amounts, session ids and payment-intent ids only.

**Idempotency, end to end.** The deposit id and the key sent to Stripe are derived from `(owner, Idempotency-Key)`, so a retried request sends byte-identical parameters and gets the same Checkout Session back instead of an idempotency error; two owners reusing a key cannot collide. Replaying a key returns the same deposit and URL for its owner (`403` for anyone else, `409` with different data), and concurrent duplicates resolve to one row, exactly like transfers.

**Webhooks are processed exactly once.** Stripe delivers at least once and out of order. The handler claims the event id as the first statement of its transaction (`INSERT ... ON CONFLICT DO NOTHING`); zero rows inserted means a redelivery and the handler answers `200` without effects. The claim and the credit commit or roll back together, so a failure gives Stripe's retry a clean second attempt, and Postgres makes concurrent duplicates wait on the in-flight row, so exactly one of them credits (covered by a concurrency test). A paid session is credited only while the deposit is `PENDING` and only when the paid amount and currency match the request; already completed, expired or mismatched cases are acknowledged without a credit. Unknown sessions and amount mismatches are written to `reconciliation_discrepancies` because an acknowledged event is never redelivered. Unhandled event types are acknowledged too; only a genuine processing failure answers `5xx`, which Stripe retries.

**Resilience.** The Stripe client runs as `Retry(CircuitBreaker(call))` (Resilience4j): 3 attempts with exponential backoff on network errors, 429, 5xx and Stripe's 409 "idempotent request in flight"; client errors (400, 401, 402, 403, idempotency key reuse) fail fast and do not trip the breaker. Transport timeouts are 3 s connect / 10 s read and the SDK's own retries are disabled so an outage is retried once per policy. An open breaker or exhausted retries answer `503` with no deposit left behind. Session creation and the reconciliation job use separate breaker instances (`stripe-checkout`, `stripe-reconciliation`), visible under `/actuator/metrics/resilience4j.circuitbreaker.state`. Tunables: `finflow.payments.stripe.resilience.*`.

**Reconciliation (report only).** A daily job (`finflow.reconciliation.cron`, 03:00 by default) lists the Checkout Sessions Stripe created in a window ending 5 minutes before now (in-flight webhooks are not "lost") and starting 48 h + 1 h of overlap earlier, and compares them with local deposits:

| Type | Meaning |
|---|---|
| `PAID_WITHOUT_LOCAL_COMPLETION` | Stripe reports the session paid; locally the deposit is missing or not `COMPLETED` (lost webhook) |
| `COMPLETED_WITHOUT_PROVIDER_PAYMENT` | The deposit is `COMPLETED` locally but Stripe has no payment for it. Must never happen |
| `AMOUNT_MISMATCH` | Paid and completed, but amount or currency differ |
| `UNKNOWN_SESSION` | A webhook referenced a session this service never created (written by the webhook handler) |

Findings are persisted (one row per type and session, reruns are idempotent), logged at `WARN` with their references and counted in `finflow.reconciliation.discrepancies` (tag `type`). The job never corrects anything: a paid session without a completed deposit usually means a lost webhook, but it can also be an amount mismatch or a session this service does not own, and crediting a balance automatically on that evidence would turn an observable inconsistency into a silent one. A human, or a deliberately designed repair tool, decides.

**Configuration.** `STRIPE_SECRET_KEY` (`sk_test_...`) and `STRIPE_WEBHOOK_SECRET` (`whsec_...`) come from the environment only (see [Configuration](#configuration)); redirect URLs, timeouts and limits are `finflow.payments.*`. Deposits are USD only and at least `0.50` (`finflow.payments.supported-currencies`, `finflow.payments.minimum-amount`), and the deposit currency must match the account currency. CI never talks to Stripe: tests mock the gateway or run the real SDK over a fake transport. The manual smoke test with the Stripe CLI is described under [Running](#stripe-webhooks-local-smoke-test).

**Future work (deliberately out of scope).** Subscriptions, payouts, refunds, KYC, multi-currency with conversion, a frontend beyond plain success/cancel pages, delayed payment methods (`checkout.session.async_payment_succeeded`), asynchronous webhook processing (the handler is a separate service so the controller can hand off to a worker), a Kafka consumer for `finflow.deposit.completed`, and an operator tool to resolve discrepancies.

## Testing

Integration tests start disposable PostgreSQL and Redis containers with Testcontainers, so the only prerequisite is a running Docker daemon. No local database, fixed port or `.env` is needed:

```bash
cd transaction-service
./mvnw verify
```

CI runs the same command on GitHub Actions, plus the alert rule tests (`promtool`) and the Kubernetes manifest validation.

If Maven cannot download dependencies on Windows because an antivirus or proxy intercepts TLS (`PKIX path building failed`), let the JVM use the Windows certificate store instead of disabling verification:

```powershell
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
```

## Performance and Resilience Testing

`perf/` holds three [k6](https://k6.io) scenarios that run against the full Docker stack. The overlay `perf/docker-compose.perf.yml` replaces Stripe with WireMock (`fake-stripe`) and gives the service throwaway credentials, so no Stripe account or key is needed. Accounts are funded through the real path, a deposit followed by a signed `checkout.session.completed` webhook; nothing is written to the database by hand. The scenarios are manual, like the Stripe smoke test, and are not part of CI.

```bash
docker compose -f docker-compose.yml -f perf/docker-compose.perf.yml up -d --build
perf/run.sh transfers                       # -e RATE=300 -e DURATION=30s to push harder
perf/run.sh webhook-duplicates
perf/run.sh stripe-outage
docker compose up -d                        # back to the normal stack
```

| Scenario | What it checks |
|---|---|
| `transfers` | Constant-rate transfers over 10 funded accounts, 80% of them between the same two accounts so they queue on the row locks. Every response is `201` or `422` (insufficient funds), and the sum of all balances is unchanged at the end. |
| `webhook-duplicates` | The same signed event delivered 200 times by 50 concurrent clients. Every delivery is acknowledged with `200` and the account is credited exactly once. |
| `stripe-outage` | Deposits at 5/s while `fake-stripe` is healthy, returns `500` for 20s, then recovers. Deposits must fail fast with `503` once the circuit breaker opens, succeed again after the open state ends, and leave no deposit row without a Stripe session. |

Results on a single development machine (12 CPUs, Docker Desktop on Windows, every container on the same host). They show behaviour and orders of magnitude, not production capacity:

| Scenario | Load | Result |
|---|---|---|
| `transfers` | 50 req/s for 60s | 3,000 transfers, 0 unexpected statuses; p95 10 ms, p99 15 ms; hot and spread accounts alike; money conserved |
| `transfers` | 300 req/s for 60s | 17,991 transfers, 0 unexpected statuses; p95 22 ms on hot accounts vs 12 ms spread, p99 137 ms vs 103 ms; money conserved |
| `transfers` | 600 req/s offered for 30s | Saturates at about 370 req/s; p95 1.03s and k6 drops the excess, but still 0 unexpected statuses and money conserved |
| `webhook-duplicates` | 200 deliveries, 50 concurrent | All `200`; balance credited once; p95 201 ms |
| `stripe-outage` | 5 deposits/s, 60s plus cooldown | Outage phase: 98 of 100 deposits rejected with `503` (the other 2 completed before the failure began), p50 6 ms because the open breaker fails fast; only the first calls paid for the retries (p99 619 ms = 3 attempts with 200/400 ms backoff). After recovery: all `201`, p95 17 ms. No orphan deposits |

What the numbers show: under contention the pessimistic locks serialize the hot accounts without errors or lost updates, and only the tail latency grows. The webhook deduplication holds under a concurrent burst. During a Stripe outage the client stops waiting on a failing dependency within a few calls.

**On the local Kubernetes cluster** (two transaction-service replicas):

```bash
OVERLAY=perf k8s/deploy.sh
TARGET=k8s perf/run.sh transfers -e RATE=100 -e DURATION=60s
TARGET=k8s perf/run.sh webhook-duplicates
TARGET=k8s perf/run.sh stripe-outage
k8s/deploy.sh                               # back to the normal overlay
```

| Scenario | Load | Result |
|---|---|---|
| `transfers` | 100 req/s for 60s | 6,001 transfers, 0 unexpected statuses, p95 27 ms, money conserved. Both pods publish outbox events (no event failed or published twice); the backlog stays under 100 events, about a second old |
| `webhook-duplicates` | 200 deliveries, 50 concurrent, spread over both pods | All `200`; balance credited once |
| `stripe-outage` | 5 deposits/s | 97 of 100 outage-phase deposits rejected with `503`, all `201` after recovery, no orphan deposits. p95 during the outage is 623 ms (31 ms with Compose): each pod has its own circuit breaker and pays for its own retries before opening |

The first cluster run found a capacity limit that the Compose runs had not looked at: the outbox publisher claimed one batch of 10 events per second per instance, so at 100 transfers/s the backlog grew to 4,823 pending events in a minute (and `OutboxNotDraining` would have fired). The publisher now keeps claiming while batches come back full, bounded per run. With one partition on `finflow.transfer.completed`, a single pod consumes the topic; more partitions would spread consumption too.

## Sharing a Snapshot

Create archives from Git so that `.env`, `.git/`, `target/` and IDE metadata are never included:

```bash
git archive --format=zip --output finflow-$(git rev-parse --short HEAD).zip HEAD
```

## Roadmap

### Completed

- [x] Core transaction service
- [x] PostgreSQL and Flyway
- [x] Transaction management
- [x] Idempotency
- [x] OpenAPI
- [x] Docker / Docker Compose
- [x] Keycloak authentication
- [x] API Gateway
- [x] Monorepo structure
- [x] CI/CD with GitHub Actions
- [x] Redis
- [x] Kafka
- [x] Transactional Outbox (lease, backoff, retry limit, metrics)
- [x] Account-level authorization (Keycloak identity → owner)
- [x] Testcontainers-based integration tests
- [x] Stripe deposits: Checkout, exactly-once webhooks, resilience, reconciliation
- [x] Observability: correlation ids end to end, structured logs, Prometheus metrics
- [x] Performance and resilience testing (k6 scenarios against a simulated Stripe)
- [x] Distributed tracing (OpenTelemetry, Tempo) and alerting (Prometheus rules, Grafana dashboard)
- [x] Kubernetes (Kustomize, local cluster, manifests validated in CI)

### Next
- [ ] Cloud deployment

## Project Goal

FinFlow is being developed incrementally to demonstrate practical experience with Java backend development, distributed systems, security, messaging, containerization, Kubernetes, CI/CD, and cloud infrastructure.

## License

This project is for educational and portfolio purposes.

## TODO / Future Improvements

The following improvements are intentionally deferred to future
iterations. They are outside the scope of the current implementation
roadmap unless required by a new feature or a correctness issue.

### 1. Transaction Reliability and Data Consistency

-   [x] Add optimistic or pessimistic locking to prevent concurrent
    transfers from causing lost balance updates or overdrafts.
-   [x] Add integration tests for concurrent transfers involving the
    same account.
-   [x] Review transaction isolation and database locking behavior under
    concurrent requests.

### 2. Security and Authorization

-   [x] Implement account-level authorization to ensure users can only
    access and manage accounts they own, unless explicitly authorized as
    administrators.
-   [x] Map Keycloak identities to application-level owners.
-   [x] Add integration tests for unauthorized account access and
    cross-user operations.
-   [ ] Review security configuration before deploying outside the local
    development environment.

### 3. Transactional Outbox Reliability

-   [x] Implement a maximum retry policy for repeatedly failing outbox
    events.
-   [x] Introduce terminal failure handling or a dead-letter mechanism
    for events that exceed the retry limit.
-   [x] Provide an operational mechanism to inspect, repair, and replay
    failed events. *(`GET /admin/outbox/failed` and
    `POST /admin/outbox/{eventId}/retry`, admin only.)*
-   [x] Add monitoring for pending events, retry counts, publication
    failures, and event processing delays.
-   [ ] Expand failure-recovery tests to cover process crashes, Kafka
    timeouts, and lease expiration.

### 4. Event-Driven Architecture

-   [x] Include stable event IDs and transaction IDs in event payloads
    to support deduplication and workflow correlation.
-   [ ] Introduce event schema versioning and compatibility rules as
    additional event types are added.
-   [x] Implement idempotent Kafka consumers using durable event
    deduplication.
-   [ ] Test duplicate message delivery and relevant out-of-order
    processing scenarios. *(Partial: sequential and concurrent
    duplicates are covered; out-of-order delivery is not, since the
    only consumer has no order-dependent effect yet.)*
-   [x] Document the at-least-once delivery guarantee and the
    possibility of duplicate events.

### 5. API and Error Handling

-   [x] Standardize HTTP error responses for domain validation failures
    and persistence conflicts.
-   [x] Return appropriate status codes for invalid requests and
    idempotency-key conflicts.
-   [x] Add controller integration tests for error response contracts.

### 6. Testing Infrastructure

-   [x] Isolate integration tests from the development database.
-   [ ] Ensure test cleanup affects only records created by the test.
    *(Less pressing now that every run uses a disposable database.)*
-   [x] Evaluate Testcontainers for disposable PostgreSQL and other
    infrastructure dependencies.
-   [x] Add CI integration tests covering database migrations,
    concurrent operations, and messaging workflows.

### 7. Observability and Operations

-   [x] Add structured logging with transaction IDs, event IDs, and
    correlation IDs.
-   [ ] Introduce metrics for transfer processing, outbox publication,
    retry activity, and consumer failures. *(Partial: outbox publication,
    Stripe retry/circuit breaker and reconciliation counters, exported to
    Prometheus; no transfer or consumer-specific metrics yet.)*
-   [x] Add distributed tracing across the API gateway, transaction
    service, and asynchronous workflows, including JDBC and Stripe
    calls.
-   [x] Define alerts for stuck outbox events and repeated publication
    failures.

### 8. Infrastructure and Configuration

-   [ ] Align gateway routes and service URLs across local and Docker
    profiles.
-   [ ] Make PostgreSQL health checks use the configured database
    credentials.
-   [x] Keep test-only configuration exclusively in test resources.
-   [x] Remove redundant database indexes after validating query plans.
-   [x] Pin infrastructure image versions and review container health
    checks.
-   [x] Separate development credentials from deployment configuration.
-   [x] Exclude local secrets, IDE metadata, Git metadata, and generated
    build artifacts from source handoffs.

### 9. Documentation

-   [x] Document the transactional outbox flow, retry policy, lease
    recovery, and delivery guarantees.
-   [ ] Document the Saga state machine, failure handling, and
    compensating actions.
-   [x] Document local development setup, integration-test execution,
    and required infrastructure.
-   [ ] Maintain an architecture decision record for significant design
    trade-offs.

### 10. Payments

-   [x] Deposits through Stripe Checkout with owner-scoped, idempotent
    creation.
-   [x] Signature-authenticated webhooks processed exactly once, with a
    concurrent-duplicate test.
-   [x] Retry, circuit breaker and timeouts around the provider client
    with a 503 contract.
-   [x] Report-only reconciliation with persisted discrepancies and a
    counter.
-   [ ] Handle delayed payment methods
    (`checkout.session.async_payment_succeeded` / `_failed`).
-   [ ] Move webhook processing to an asynchronous worker once volume
    requires it.
-   [ ] Add a Kafka consumer for `finflow.deposit.completed`.
-   [ ] Provide an operator tool to resolve reconciliation discrepancies
    (never automatic).
-   [ ] Refunds, payouts, subscriptions, KYC and multi-currency remain out
    of scope.

------------------------------------------------------------------------

**Roadmap scope:** the immediate roadmap remains focused on transaction
correctness, test isolation, Saga orchestration, and idempotent event
consumption. The items above are a backlog, not a prerequisite
checklist.

When a deferred item becomes necessary for a feature---for example,
durable event IDs when building consumer deduplication---promote it into
that milestone rather than leaving it deferred indefinitely.