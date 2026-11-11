FROM maven:3.9.13-eclipse-temurin-21 AS builder

WORKDIR /app

# Resolve dependencies in their own layer so source-only changes reuse it.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src

RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Fixed numeric ids, so Kubernetes can enforce runAsNonRoot.
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app

COPY --from=builder --chown=app:app /app/target/transaction-service-0.0.1-SNAPSHOT.jar app.jar

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
