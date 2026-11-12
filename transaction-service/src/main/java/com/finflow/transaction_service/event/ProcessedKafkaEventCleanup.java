package com.finflow.transaction_service.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Purges deduplication claims older than {@code finflow.kafka.dedup.retention}
 * (14 days by default, twice Kafka's default retention): past that window
 * the topic no longer holds the event, so it cannot be redelivered.
 */
@Component
@Profile("!test")
public class ProcessedKafkaEventCleanup {

    private static final Logger log =
            LoggerFactory.getLogger(ProcessedKafkaEventCleanup.class);

    private final ProcessedKafkaEventRepository repository;
    private final Duration retention;
    private final Clock clock;

    @Autowired
    public ProcessedKafkaEventCleanup(
            ProcessedKafkaEventRepository repository,
            @Value("${finflow.kafka.dedup.retention:P14D}") Duration retention
    ) {
        this(repository, retention, Clock.systemUTC());
    }

    ProcessedKafkaEventCleanup(
            ProcessedKafkaEventRepository repository,
            Duration retention,
            Clock clock
    ) {
        this.repository = repository;
        this.retention = retention;
        this.clock = clock;
    }

    @Scheduled(cron = "${finflow.kafka.dedup.cleanup-cron:0 30 3 * * *}")
    public void purge() {
        Instant cutoff = clock.instant().minus(retention);
        int deleted = repository.deleteProcessedBefore(cutoff);

        if (deleted > 0) {
            log.info("Purged {} processed Kafka events older than {}", deleted, cutoff);
        }
    }
}
