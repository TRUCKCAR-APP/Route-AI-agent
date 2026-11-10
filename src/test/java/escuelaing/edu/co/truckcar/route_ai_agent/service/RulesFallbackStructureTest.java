package escuelaing.edu.co.truckcar.route_ai_agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifica que cuando el modelo falla (timeout, excepción, throttling) el fallback a reglas
 * devuelve un {@link AlertInterpretation} con estructura completa y sin campos nulos críticos.
 * <p>
 * No modifica {@link TransientModelFailureTest}; esa clase prueba solo la lógica de
 * {@link TransientModelFailure}. Esta clase prueba el contrato del fallback end-to-end.
 */
class RulesFallbackStructureTest {

    private static final String ALERT_TEXT  = "Derrumbe total en la vía hacia La Línea";
    private static final String CORRIDOR    = "IBAGUE-ARMENIA";

    private final BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
    private final RuleBasedAlertInterpreter rules = new RuleBasedAlertInterpreter();

    private BedrockAlertInterpreter interpreter() {
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(1)
                .waitDuration(Duration.ofMillis(1))
                .retryOnException(new TransientModelFailure())
                .ignoreExceptions(
                        ModelAlertInterpreter.InvalidModelResponseException.class)
                .build());
        return new BedrockAlertInterpreter(
                new ObjectMapper(), rules,
                CircuitBreakerRegistry.ofDefaults(), retries,
                TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofMillis(200)).build()),
                "amazon.nova-micro-v1:0", "us-east-1", client);
    }

    // ── 1. Throttling (429) → fallback inmediato ──────────────────────────────

    @Test
    void whenModelThrottlesTheFallbackHasFullStructure() {
        when(client.converse(any(ConverseRequest.class)))
                .thenThrow(ThrottlingException.builder().statusCode(429).message("quota").build());

        AlertInterpretation result = interpreter().interpret(ALERT_TEXT, CORRIDOR);

        assertFallbackStructureIsComplete(result);
    }

    // ── 2. Excepción de disponibilidad → fallback ─────────────────────────────

    @Test
    void whenModelThrowsExceptionTheFallbackHasFullStructure() {
        when(client.converse(any(ConverseRequest.class)))
                .thenThrow(ServiceUnavailableException.builder().statusCode(503).message("outage").build());

        AlertInterpretation result = interpreter().interpret(ALERT_TEXT, CORRIDOR);

        assertFallbackStructureIsComplete(result);
    }

    // ── 3. Respuesta con JSON inválido → fallback ─────────────────────────────

    @Test
    void whenModelReturnsInvalidJsonTheFallbackHasFullStructure() {
        // La fábrica de respuestas del BedrockAlertInterpreterTest devuelve texto puro,
        // aquí mockeamos directamente para que parse() lance InvalidModelResponseException.
        when(client.converse(any(ConverseRequest.class)))
                .thenThrow(new ModelAlertInterpreter.InvalidModelResponseException(
                        "respuesta malformada", new IllegalArgumentException("bad json")));

        AlertInterpretation result = interpreter().interpret(ALERT_TEXT, CORRIDOR);

        assertFallbackStructureIsComplete(result);
    }

    // ── Aserción compartida ───────────────────────────────────────────────────

    private static void assertFallbackStructureIsComplete(AlertInterpretation result) {
        // Viene de las reglas
        assertThat(result.source()).isEqualTo(RuleBasedAlertInterpreter.SOURCE);

        // Campos obligatorios no nulos
        assertThat(result.severity()).isNotNull().isIn("LOW", "MEDIUM", "HIGH", "CRITICAL");
        assertThat(result.affectedCorridor()).isNotNull();
        assertThat(result.alertType()).isNotNull();   // nuevo campo
        assertThat(result.voiceText()).isNotBlank();   // texto para voz

        // Confianza en rango válido
        assertThat(result.confidenceScore()).isBetween(0.0, 1.0);

        // Para DERRUMBE el corredor debería bloquearse
        assertThat(result.isCorridorBlocked()).isTrue();
        assertThat(result.alertType()).isEqualTo("DERRUMBE");
    }
}
