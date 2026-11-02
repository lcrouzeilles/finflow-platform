package com.finflow.transaction_service.payment.stripe;

import com.stripe.exception.StripeException;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource;
import com.stripe.net.BaseAddress;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponseGetter;

import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Replaces the SDK's HTTP transport so the real {@code StripeClient},
 * parameter serialization and exception mapping run without any network.
 * Each request is counted and its parameters and options are captured.
 */
public class FakeStripeTransport implements StripeResponseGetter {

    public final AtomicInteger calls = new AtomicInteger();
    public final List<Map<String, Object>> params = new ArrayList<>();
    public final List<RequestOptions> options = new ArrayList<>();

    private volatile Supplier<StripeException> failure;
    private volatile StripeObject response;

    public FakeStripeTransport respondWith(StripeObject object) {
        this.response = object;
        this.failure = null;
        return this;
    }

    public FakeStripeTransport failWith(Supplier<StripeException> exception) {
        this.failure = exception;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends StripeObject> T request(
            BaseAddress baseAddress,
            ApiResource.RequestMethod method,
            String path,
            Map<String, Object> requestParams,
            Type typeToken,
            RequestOptions requestOptions,
            ApiMode apiMode
    ) throws StripeException {
        calls.incrementAndGet();
        params.add(requestParams);
        options.add(requestOptions);

        Supplier<StripeException> currentFailure = failure;
        if (currentFailure != null) {
            throw currentFailure.get();
        }

        return (T) response;
    }

    @Override
    public InputStream requestStream(
            BaseAddress baseAddress,
            ApiResource.RequestMethod method,
            String path,
            Map<String, Object> requestParams,
            RequestOptions requestOptions,
            ApiMode apiMode
    ) {
        throw new UnsupportedOperationException("Not used by the gateway");
    }
}
