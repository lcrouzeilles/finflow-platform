# FinFlow Transaction Platform

[![CI](https://github.com/lcrouzeilles/finflow-platform/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/lcrouzeilles/finflow-platform/actions/workflows/ci.yml)

FinFlow is a production-oriented backend platform for processing financial transactions, built as a portfolio and learning project.

The project focuses on modern backend engineering, security, distributed systems, and cloud-native infrastructure.

## Architecture

```text
Client
  │
  ▼
API Gateway
  │
  ▼
Transaction Service
  │
  ▼
PostgreSQL

Keycloak ──► JWT Authentication
```

The API Gateway is the public entry point. The Transaction Service runs internally and is not directly exposed when using Docker.

## Repository

```text
finflow-transaction-platform/
├── api-gateway/
├── transaction-service/
├── keycloak/
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
- OpenAPI / Swagger
- Docker / Docker Compose
- Testcontainers
- Maven

## Current Features

- REST API for accounts and transfers
- Transaction management and rollback
- Pessimistic row locking with deterministic lock ordering for concurrent transfers
- Idempotency support
- Account-level authorization: the owner is derived from the JWT, never from the request body
- PostgreSQL persistence
- Flyway database migrations
- JWT authentication and role-based authorization
- Keycloak identity management
- API Gateway routing and security
- Dockerized local environment
- Integration testing with Testcontainers
- OpenAPI documentation

## Docker Environment

| Service | Host Port | Container Port |
|---|---:|---:|
| API Gateway | `8080` | `8080` |
| Keycloak | `8081` | `8080` |
| PostgreSQL | `55433` | `5432` |
| Transaction Service | — | `8080` |

The Transaction Service is accessible internally through:

```text
http://transaction-service:8080
```

## Running

Start the complete platform from the repository root:

```bash
docker compose up --build
```

Check the services:

```bash
docker compose ps
```

API Gateway:

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

## Security Model

- Authentication is delegated to Keycloak. The gateway and the service both validate the JWT; realm roles become `ROLE_USER` / `ROLE_ADMIN`.
- Identity mapping: the `sub` claim of the token is bound to an application `Owner` (`owners.keycloak_subject`, unique). An owner is provisioned automatically on the first authenticated request.
- Ownership is enforced in the service layer and derived from the token only:
  - `POST /accounts` creates the account for the authenticated owner; any `ownerId` in the body is ignored.
  - `GET /accounts/{id}` returns `404` for accounts of other owners (the same response as for a nonexistent ID, so account IDs cannot be probed).
  - `POST /transfers` requires the source account to belong to the caller (`403` otherwise). Transfers to accounts of other owners are allowed.
  - An idempotency key can only be replayed by the owner who created the transaction (`403` otherwise), so a foreign key cannot be used to read someone else's transaction.

## Testing

Integration tests start disposable PostgreSQL and Redis containers with Testcontainers, so the only prerequisite is a running Docker daemon. No local database, fixed port or `.env` is needed:

```bash
cd transaction-service
./mvnw verify
```

CI runs the same command on GitHub Actions.

If Maven cannot download dependencies on Windows because an antivirus or proxy intercepts TLS (`PKIX path building failed`), let the JVM use the Windows certificate store instead of disabling verification:

```powershell
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
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
- [x] Account-level authorization (Keycloak identity → owner)
- [x] Testcontainers-based integration tests

### Next
- [ ] Kafka
- [ ] Transactional Outbox
- [ ] Kubernetes
- [ ] Observability
- [ ] Cloud deployment
- [ ] Performance and resilience testing

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

-   [ ] Implement a maximum retry policy for repeatedly failing outbox
    events.
-   [ ] Introduce terminal failure handling or a dead-letter mechanism
    for events that exceed the retry limit.
-   [ ] Provide an operational mechanism to inspect, repair, and replay
    failed events.
-   [ ] Add monitoring for pending events, retry counts, publication
    failures, and event processing delays.
-   [ ] Expand failure-recovery tests to cover process crashes, Kafka
    timeouts, and lease expiration.

### 4. Event-Driven Architecture

-   [ ] Include stable event IDs and transaction IDs in event payloads
    to support deduplication and workflow correlation.
-   [ ] Introduce event schema versioning and compatibility rules as
    additional event types are added.
-   [ ] Implement idempotent Kafka consumers using durable event
    deduplication.
-   [ ] Test duplicate message delivery and relevant out-of-order
    processing scenarios.
-   [ ] Document the at-least-once delivery guarantee and the
    possibility of duplicate events.

### 5. API and Error Handling

-   [ ] Standardize HTTP error responses for domain validation failures
    and persistence conflicts.
-   [ ] Return appropriate status codes for invalid requests and
    idempotency-key conflicts.
-   [ ] Add controller integration tests for error response contracts.

### 6. Testing Infrastructure

-   [x] Isolate integration tests from the development database.
-   [ ] Ensure test cleanup affects only records created by the test.
    *(Less pressing now that every run uses a disposable database.)*
-   [x] Evaluate Testcontainers for disposable PostgreSQL and other
    infrastructure dependencies.
-   [x] Add CI integration tests covering database migrations,
    concurrent operations, and messaging workflows.

### 7. Observability and Operations

-   [ ] Add structured logging with transaction IDs, event IDs, and
    correlation IDs.
-   [ ] Introduce metrics for transfer processing, outbox publication,
    retry activity, and consumer failures.
-   [ ] Add distributed tracing across the API gateway, transaction
    service, and asynchronous workflows.
-   [ ] Define alerts for stuck outbox events and repeated publication
    failures.

### 8. Infrastructure and Configuration

-   [ ] Align gateway routes and service URLs across local and Docker
    profiles.
-   [ ] Make PostgreSQL health checks use the configured database
    credentials.
-   [x] Keep test-only configuration exclusively in test resources.
-   [ ] Remove redundant database indexes after validating query plans.
-   [ ] Pin infrastructure image versions and review container health
    checks.
-   [ ] Separate development credentials from deployment configuration.
-   [ ] Exclude local secrets, IDE metadata, Git metadata, and generated
    build artifacts from source handoffs.

### 9. Documentation

-   [ ] Document the transactional outbox flow, retry policy, lease
    recovery, and delivery guarantees.
-   [ ] Document the Saga state machine, failure handling, and
    compensating actions.
-   [x] Document local development setup, integration-test execution,
    and required infrastructure.
-   [ ] Maintain an architecture decision record for significant design
    trade-offs.

------------------------------------------------------------------------

**Roadmap scope:** the immediate roadmap remains focused on transaction
correctness, test isolation, Saga orchestration, and idempotent event
consumption. The items above are a backlog, not a prerequisite
checklist.

When a deferred item becomes necessary for a feature---for example,
durable event IDs when building consumer deduplication---promote it into
that milestone rather than leaving it deferred indefinitely.