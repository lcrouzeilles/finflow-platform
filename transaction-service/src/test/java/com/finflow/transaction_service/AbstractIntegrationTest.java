package com.finflow.transaction_service;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for every test that needs a real database or cache.
 *
 * <p>Starts one disposable PostgreSQL and one Redis container per JVM and
 * wires them into Spring Boot through {@link ServiceConnection}, so no test
 * depends on a pre-existing local or CI service, a fixed port, or a shared
 * database. The containers are started eagerly in a static initializer (not
 * through the Testcontainers JUnit extension) so that all cached Spring
 * contexts in the run share the same instances; Ryuk removes them when the
 * JVM exits.
 */
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7")
                    .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }
}
