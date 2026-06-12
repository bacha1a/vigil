package io.vigil.autoconfigure;

import io.vigil.autoconfigure.http.VigilRestTemplateCustomizer;

import io.vigil.core.model.IdempotencyKey;
import io.vigil.scheduler.context.HttpIdempotencyContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VigilRestTemplateCustomizerTest {

    @AfterEach
    void cleanThreadLocal() {
        HttpIdempotencyContext.unbind();
    }

    @Test
    void customizerAddsExactlyOneInterceptor() {
        var rt = new RestTemplate();
        int before = rt.getInterceptors().size();

        new VigilRestTemplateCustomizer().customize(rt);

        assertThat(rt.getInterceptors()).hasSize(before + 1);
    }

    @Test
    void interceptorAddsBothIdempotencyHeadersWhenContextBound() throws IOException {
        var rt = new RestTemplate();
        new VigilRestTemplateCustomizer().customize(rt);
        var interceptor = rt.getInterceptors().get(0);

        HttpIdempotencyContext.bind(IdempotencyKey.of(42L, "customer-7"));

        var request = mock(HttpRequest.class);
        var headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create("http://example.com/charge"));

        var response = mock(ClientHttpResponse.class);
        var execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);

        var actual = interceptor.intercept(request, new byte[0], execution);

        assertThat(actual).isSameAs(response);
        assertThat(headers.getFirst("Idempotency-Key")).isEqualTo("vigil_42_customer-7");
        assertThat(headers.getFirst("X-Idempotency-Key")).isEqualTo("vigil_42_customer-7");
    }

    @Test
    void interceptorAddsNoHeaderWhenContextUnbound() throws IOException {
        var rt = new RestTemplate();
        new VigilRestTemplateCustomizer().customize(rt);
        var interceptor = rt.getInterceptors().get(0);

        var request = mock(HttpRequest.class);
        var headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);

        var response = mock(ClientHttpResponse.class);
        var execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);

        interceptor.intercept(request, new byte[0], execution);

        assertThat(headers.getFirst("Idempotency-Key")).isNull();
        assertThat(headers.getFirst("X-Idempotency-Key")).isNull();
    }
}
