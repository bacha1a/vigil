package io.vigil.autoconfigure.http;

import io.vigil.core.model.IdempotencyKey;
import io.vigil.scheduler.context.HttpIdempotencyContext;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

public class VigilRestTemplateCustomizer implements RestTemplateCustomizer {

    @Override
    public void customize(@NonNull RestTemplate restTemplate) {
        restTemplate.getInterceptors().add(new IdempotencyKeyInterceptor());
    }

    static class IdempotencyKeyInterceptor implements ClientHttpRequestInterceptor {

        @Override
        public @NonNull ClientHttpResponse intercept(@NonNull HttpRequest request, @NonNull byte[] body,
                                                     @NonNull ClientHttpRequestExecution execution) throws IOException {
            IdempotencyKey key = HttpIdempotencyContext.current();
            if (key != null) {
                request.getHeaders().set("Idempotency-Key",   key.value());
                request.getHeaders().set("X-Idempotency-Key", key.value());
            }
            return execution.execute(request, body);
        }
    }
}
