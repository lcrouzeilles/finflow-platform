# FinFlow Transaction Platform

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
- Maven

## Current Features

- REST API for accounts and transfers
- Transaction management and rollback
- Idempotency support
- PostgreSQL persistence
- Flyway database migrations
- JWT authentication and role-based authorization
- Keycloak identity management
- API Gateway routing and security
- Dockerized local environment
- Integration testing
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

### Next

- [ ] CI/CD with GitHub Actions
- [ ] Redis
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