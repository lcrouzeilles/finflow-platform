package com.finflow.transaction_service.event;

import com.finflow.transaction_service.config.CorrelationIdFilter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferEventProducerTest {

    @Mock
    private KafkaTemplate<String, TransferCompletedEvent> kafkaTemplate;

    private TransferEventProducer producer;

    private final TransferCompletedEvent event = new TransferCompletedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()
    );

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        producer = new TransferEventProducer(kafkaTemplate);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void shouldSendTheCorrelationIdAsAHeader() throws Exception {
        producer.publishAndAwait(event, "demo-123");

        Header header = sentRecord().headers().lastHeader(CorrelationIdFilter.HEADER);
        assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo("demo-123");
    }

    @Test
    void shouldSendNoHeaderWithoutACorrelationId() throws Exception {
        producer.publishAndAwait(event, null);

        ProducerRecord<String, TransferCompletedEvent> record = sentRecord();
        assertThat(record.headers().lastHeader(CorrelationIdFilter.HEADER)).isNull();
        assertThat(record.key()).isEqualTo(event.sourceAccountId().toString());
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, TransferCompletedEvent> sentRecord() {
        ArgumentCaptor<ProducerRecord<String, TransferCompletedEvent>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }
}
