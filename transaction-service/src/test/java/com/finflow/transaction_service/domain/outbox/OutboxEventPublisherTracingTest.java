package com.finflow.transaction_service.domain.outbox;

import com.finflow.transaction_service.service.OutboxEventClaimer;
import com.finflow.transaction_service.service.OutboxEventPublisher;
import com.finflow.transaction_service.service.OutboxEventRelay;
import com.finflow.transaction_service.service.OutboxEventStatusService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * The publication of an outbox event continues the trace of the request
 * that wrote it, with the real OpenTelemetry bridge used by the service.
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTracingTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String REQUEST_SPAN_ID = "00f067aa0ba902b7";

    @Mock
    private OutboxEventClaimer claimer;

    @Mock
    private OutboxEventStatusService statusService;

    @Mock
    private OutboxEventRelay relay;

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private SdkTracerProvider tracerProvider;
    private OutboxEventPublisher publisher;

    /** The span that is current while the relay sends to Kafka. */
    private final AtomicReference<SpanContext> spanDuringSend = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        io.opentelemetry.api.trace.Tracer otelTracer = tracerProvider.get("test");
        OtelTracer tracer = new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> { });
        OtelPropagator propagator = new OtelPropagator(
                ContextPropagators.create(W3CTraceContextPropagator.getInstance()),
                otelTracer
        );

        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator)
        );

        when(relay.eventType()).thenReturn("TRANSFER_COMPLETED");
        doAnswer(invocation -> {
            spanDuringSend.set(Span.current().getSpanContext());
            return null;
        }).when(relay).publishAndAwait(any(), any());

        publisher = new OutboxEventPublisher(
                claimer,
                statusService,
                List.of(relay),
                new SimpleMeterRegistry(),
                observationRegistry
        );
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    void shouldPublishAsAChildOfTheRequestThatWroteTheEvent() {
        OutboxEvent event;
        SpanContext request = SpanContext.create(
                TRACE_ID, REQUEST_SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault()
        );
        try (Scope ignored = Span.wrap(request).makeCurrent()) {
            event = claimed(new OutboxEvent("TRANSACTION", UUID.randomUUID(), "TRANSFER_COMPLETED", "{}"));
        }
        when(claimer.claimBatch(anyInt(), any())).thenReturn(List.of(event));

        publisher.publishBatch();

        SpanData publish = onlySpan();
        assertThat(publish.getName()).isEqualTo("outbox publish");
        assertThat(publish.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(publish.getParentSpanId()).isEqualTo(REQUEST_SPAN_ID);
        // The Kafka send runs inside it, so the producer span is its child.
        assertThat(spanDuringSend.get().getSpanId()).isEqualTo(publish.getSpanId());
    }

    @Test
    void shouldStartANewTraceWhenTheEventHasNoTraceParent() {
        OutboxEvent event = claimed(
                new OutboxEvent("TRANSACTION", UUID.randomUUID(), "TRANSFER_COMPLETED", "{}")
        );
        when(claimer.claimBatch(anyInt(), any())).thenReturn(List.of(event));

        publisher.publishBatch();

        SpanData publish = onlySpan();
        assertThat(publish.getParentSpanContext().isValid()).isFalse();
        assertThat(publish.getTraceId()).isNotEqualTo(TRACE_ID);
        assertThat(spanDuringSend.get().getSpanId()).isEqualTo(publish.getSpanId());
    }

    private static OutboxEvent claimed(OutboxEvent event) {
        event.claim("worker-1", Instant.now().plusSeconds(180));
        return event;
    }

    private SpanData onlySpan() {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        return spans.getFirst();
    }
}
