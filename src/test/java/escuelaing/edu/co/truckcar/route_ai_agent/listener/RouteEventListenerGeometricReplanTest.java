package escuelaing.edu.co.truckcar.route_ai_agent.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.event.EventEnvelope;
import escuelaing.edu.co.truckcar.route_ai_agent.service.AlertInterpretation;
import escuelaing.edu.co.truckcar.route_ai_agent.service.AlertInterpreter;
import escuelaing.edu.co.truckcar.route_ai_agent.service.IRouteAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Las alertas del teléfono no traen corredor con nombre: si el modelo detecta un bloqueo con confianza, el replanteo se
 * publica con la posición y el texto de la alerta para que la PWA decida si cae sobre su ruta.
 */
class RouteEventListenerGeometricReplanTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private KafkaTemplate<String, Object> kafkaTemplate;
    private AlertInterpreter interpreter;
    private RouteEventListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        interpreter = mock(AlertInterpreter.class);
        listener = new RouteEventListener(mapper, mock(IRouteAgentService.class), kafkaTemplate, interpreter);
    }

    private String event(String type, ObjectNode payload) throws Exception {
        return mapper.writeValueAsString(mapper.createObjectNode()
                .put("eventId", UUID.randomUUID().toString())
                .put("eventType", type)
                .put("occurredAt", Instant.now().toString())
                .put("version", 1)
                .put("producer", "community-alerts")
                .set("payload", payload));
    }

    private ObjectNode phoneAlert(boolean withPosition) {
        ObjectNode payload = mapper.createObjectNode()
                .put("alertId", 21)
                .put("alertType", "DERRUMBE")
                .put("title", "Derrumbe")
                .put("description", "Derrumbe tapa la vía en el km 40, no pasan camiones")
                .put("text", "Derrumbe tapa la vía en el km 40, no pasan camiones")
                .put("confirmationsCount", 1);
        if (withPosition) {
            payload.put("latitude", 4.7012).put("longitude", -74.0425);
        }
        return payload;
    }

    private JsonNode publishedPayload(String key) {
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("truckcar.events.route-requests"), eq(key), sent.capture());
        EventEnvelope envelope = (EventEnvelope) sent.getValue();
        assertThat(envelope.getEventType()).isEqualTo("route.replan.requested");
        return envelope.getPayload();
    }

    @Test
    void aBlockageWithoutCorridorIsPublishedWithItsPositionTextAndConfidence() throws Exception {
        when(interpreter.interpret(anyString(), any()))
                .thenReturn(new AlertInterpretation(true, "", "HIGH", 0.9, "AZURE_OPENAI"));

        listener.consume(event("alert.reported", phoneAlert(true)));

        JsonNode payload = publishedPayload("alert-21");
        assertThat(payload.get("alertId").asLong()).isEqualTo(21);
        assertThat(payload.get("latitude").asDouble()).isEqualTo(4.7012);
        assertThat(payload.get("longitude").asDouble()).isEqualTo(-74.0425);
        assertThat(payload.get("alertType").asText()).isEqualTo("DERRUMBE");
        assertThat(payload.get("description").asText()).contains("tapa la vía");
        assertThat(payload.get("confidence").asDouble()).isEqualTo(0.9);
        assertThat(payload.get("source").asText()).isEqualTo("AZURE_OPENAI");
        assertThat(payload.get("severity").asText()).isEqualTo("HIGH");
    }

    @Test
    void withoutCorridorNorPositionThereIsNothingToLocateAndNoReplan() throws Exception {
        when(interpreter.interpret(anyString(), any()))
                .thenReturn(new AlertInterpretation(true, "", "HIGH", 0.9, "AZURE_OPENAI"));

        listener.consume(event("alert.reported", phoneAlert(false)));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void aNonBlockingInterpretationNeverReplans() throws Exception {
        when(interpreter.interpret(anyString(), any()))
                .thenReturn(new AlertInterpretation(false, "", "LOW", 0.95, "AZURE_OPENAI"));

        listener.consume(event("alert.reported", phoneAlert(true)));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void aCommunityValidatedAlertWithPositionReplansAsCommunity() throws Exception {
        ObjectNode payload = phoneAlert(true).put("activeRoute", true).put("severity", "ALTA");

        listener.consume(event("alert.validated", payload));

        JsonNode published = publishedPayload("alert-21");
        assertThat(published.get("source").asText()).isEqualTo("COMMUNITY");
        assertThat(published.get("confidence").isNull()).isTrue();
        assertThat(published.get("latitude").asDouble()).isEqualTo(4.7012);
    }
}
