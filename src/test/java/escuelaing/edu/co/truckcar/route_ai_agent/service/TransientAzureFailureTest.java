package escuelaing.edu.co.truckcar.route_ai_agent.service;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpResponse;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Una cuota agotada (429) o una clave inválida no se reintentan: el fallback por reglas responde sin esperar. */
class TransientAzureFailureTest {

    private final TransientAzureFailure transientFailure = new TransientAzureFailure();

    private static HttpResponseException http(int status) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        return new HttpResponseException("Azure respondió " + status, response);
    }

    @Test
    void quotaAuthAndBadRequestsAreNotRetried() {
        assertThat(transientFailure.test(http(429))).isFalse();
        assertThat(transientFailure.test(http(401))).isFalse();
        assertThat(transientFailure.test(http(403))).isFalse();
        assertThat(transientFailure.test(http(400))).isFalse();
        // También cuando llega envuelto por el TimeLimiter o el ejecutor.
        assertThat(transientFailure.test(new ExecutionException(new IllegalStateException(http(429))))).isFalse();
    }

    @Test
    void serverErrorsTimeoutsAndNetworkFailuresAreRetried() {
        assertThat(transientFailure.test(http(503))).isTrue();
        assertThat(transientFailure.test(http(500))).isTrue();
        assertThat(transientFailure.test(http(408))).isTrue();
        assertThat(transientFailure.test(new TimeoutException())).isTrue();
        assertThat(transientFailure.test(new java.net.ConnectException("sin red"))).isTrue();
    }

    @Test
    void withTheConfiguredRetryA429IsCalledOnceAndA503ThreeTimes() {
        RetryConfig config = RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1))
                .retryOnException(transientFailure).build();

        AtomicInteger quotaCalls = new AtomicInteger();
        Supplier<String> quota = Retry.decorateSupplier(Retry.of("cuota", config), () -> {
            quotaCalls.incrementAndGet();
            throw http(429);
        });
        assertThatThrownBy(quota::get).isInstanceOf(HttpResponseException.class);
        assertThat(quotaCalls).hasValue(1);

        AtomicInteger outageCalls = new AtomicInteger();
        Supplier<String> outage = Retry.decorateSupplier(Retry.of("caida", config), () -> {
            outageCalls.incrementAndGet();
            throw http(503);
        });
        assertThatThrownBy(outage::get).isInstanceOf(HttpResponseException.class);
        assertThat(outageCalls).hasValue(3);
    }
}
