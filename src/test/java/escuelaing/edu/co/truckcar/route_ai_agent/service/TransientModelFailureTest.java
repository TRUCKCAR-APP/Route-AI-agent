package escuelaing.edu.co.truckcar.route_ai_agent.service;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpResponse;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Una cuota agotada (429, throttling) o una clave o permiso inválido no se reintentan: las reglas responden sin esperar. */
class TransientModelFailureTest {

    private final TransientModelFailure transientFailure = new TransientModelFailure();

    private static HttpResponseException http(int status) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        return new HttpResponseException("Azure respondió " + status, response);
    }

    @Test
    void azureQuotaAuthAndBadRequestsAreNotRetried() {
        assertThat(transientFailure.test(http(429))).isFalse();
        assertThat(transientFailure.test(http(401))).isFalse();
        assertThat(transientFailure.test(http(403))).isFalse();
        assertThat(transientFailure.test(http(400))).isFalse();
        // También cuando llega envuelto por el TimeLimiter o el ejecutor.
        assertThat(transientFailure.test(new ExecutionException(new IllegalStateException(http(429))))).isFalse();
    }

    @Test
    void azureServerErrorsTimeoutsAndNetworkFailuresAreRetried() {
        assertThat(transientFailure.test(http(503))).isTrue();
        assertThat(transientFailure.test(http(500))).isTrue();
        assertThat(transientFailure.test(http(408))).isTrue();
        assertThat(transientFailure.test(new TimeoutException())).isTrue();
        assertThat(transientFailure.test(new java.net.ConnectException("sin red"))).isTrue();
    }

    @Test
    void bedrockThrottlingPermissionsAndMissingModelAreNotRetried() {
        assertThat(transientFailure.test(ThrottlingException.builder().statusCode(429).message("cuota").build()))
                .isFalse();
        assertThat(transientFailure.test(AccessDeniedException.builder().statusCode(403).message("LabRole").build()))
                .isFalse();
        assertThat(transientFailure.test(ResourceNotFoundException.builder().statusCode(404).message("modelo").build()))
                .isFalse();
        assertThat(transientFailure.test(new ExecutionException(
                ThrottlingException.builder().statusCode(429).message("cuota").build()))).isFalse();
    }

    @Test
    void bedrockOutagesAndClientFailuresAreRetried() {
        assertThat(transientFailure.test(ServiceUnavailableException.builder().statusCode(503).message("caída").build()))
                .isTrue();
        assertThat(transientFailure.test(SdkClientException.create("sin red"))).isTrue();
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
