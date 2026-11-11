package com.finflow.apigateway.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void shouldKeepAWellFormedIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "demo-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                mdcDuringRequest.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("demo-123");
        assertThat(mdcDuringRequest.get()).isEqualTo("demo-123");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void shouldGenerateAnIdWhenTheHeaderIsMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        String generated = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(UUID.fromString(generated)).isNotNull();
    }

    @Test
    void shouldReplaceAMalformedOrOversizedId() {
        assertThat(CorrelationIdFilter.resolve("bad value!")).isNotEqualTo("bad value!");
        assertThat(CorrelationIdFilter.resolve("a".repeat(65))).hasSize(36);
        assertThat(CorrelationIdFilter.resolve("")).hasSize(36);
    }

    @Test
    void shouldNotDuplicateTheHeaderEchoedByTheRoutedService() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "demo-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                ((HttpServletResponse) res).addHeader(CorrelationIdFilter.HEADER, "demo-123"));

        assertThat(response.getHeaders(CorrelationIdFilter.HEADER)).containsExactly("demo-123");
    }

    @Test
    void shouldForwardTheResolvedIdToTheRoutedRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "bad value!");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
        String forwardedId = forwarded.getHeader(CorrelationIdFilter.HEADER);
        assertThat(forwardedId).isNotEqualTo("bad value!");
        assertThat(UUID.fromString(forwardedId)).isNotNull();
        assertThat(Collections.list(forwarded.getHeaderNames()))
                .containsOnlyOnce(CorrelationIdFilter.HEADER);
    }
}
