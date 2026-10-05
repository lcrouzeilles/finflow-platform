package com.finflow.transaction_service.cache;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class RedisAvailability {

    private static final Duration COOLDOWN = Duration.ofSeconds(10);

    private final AtomicReference<Instant> unavailableUntil =
            new AtomicReference<>();

    public boolean isAvailable() {
        Instant until = unavailableUntil.get();

        return until == null || Instant.now().isAfter(until);
    }

    public void markFailure() {
        unavailableUntil.set(
                Instant.now().plus(COOLDOWN)
        );
    }

}