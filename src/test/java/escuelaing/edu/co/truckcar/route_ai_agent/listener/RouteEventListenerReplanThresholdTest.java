package escuelaing.edu.co.truckcar.route_ai_agent.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import escuelaing.edu.co.truckcar.route_ai_agent.service.AzureOpenAiAlertInterpreterService;
import escuelaing.edu.co.truckcar.route_ai_agent.service.AzureOpenAiAlertInterpreterService.AlertInterpretation;
import escuelaing.edu.co.truckcar.route_ai_agent.service.IRouteAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** alert.reported solo replantea si la confianza es >= 0.70 o la comunidad ya confirmó la alerta. */
class RouteEventListenerReplanThresholdTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private KafkaTemplate<String, Object> kafkaTemplate;
    private AzureOpenAiAlertInterpreterService interpreter;
    private RouteEventListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        interpreter = mock(AzureOpenAiAlertInterpreterService.class);
        listener = new RouteEventListener(mapper, mock(IRouteAgentService.class), kafkaTemplate, interpreter);
    }

    private String reported(int confirmations) throws Exception {
        ObjectNode payload = mapper.createObjectNode()
                .put("alertId", 21)
                .put("text", "Derrumbe en el alto de La Línea")
                .put("roadCorridor", "IBAGUE-ARMENIA")
                .put("confirmationsCount", confirmations);
        return mapper.writeValueAsString(mapper.createObjectNode()
                .put("eventId", UUID.randomUUID().toString())
                .put("eventType", "alert.reported")
                .put("occurredAt", Instant.now().toString())
                .put("version", 1)
                .put("producer", "community-alerts")
                .set("payload", payload));
    }

    private void interpretedAs(double confidence, String source) {
        when(interpreter.interpret(anyString(), eq("IBAGUE-ARMENIA")))
                .thenReturn(new AlertInterpretation(true, "IBAGUE-ARMENIA", "HIGH", confidence, source));
    }

    @Test
    void highConfidenceBlockingPublishesReplan() throws Exception {
        interpretedAs(0.92, "AZURE_OPENAI");

        listener.consume(reported(1));

        verify(kafkaTemplate).send(eq("truckcar.events.route-requests"), eq("IBAGUE-ARMENIA"), any());
    }

    @Test
    void lowConfidenceUnconfirmedAlertDoesNotReplan() throws Exception {
        interpretedAs(0.6, "RULES");

        listener.consume(reported(1));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void lowConfidenceButConfirmedAlertReplans() throws Exception {
        interpretedAs(0.6, "RULES");

        listener.consume(reported(3));

        verify(kafkaTemplate).send(eq("truckcar.events.route-requests"), eq("IBAGUE-ARMENIA"), any());
    }
}
