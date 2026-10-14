package escuelaing.edu.co.truckcar.route_ai_agent.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import escuelaing.edu.co.truckcar.route_ai_agent.service.IRouteAgentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RouteEventListenerTest {

    @Test
    void validatedAlertOnActiveCorridorPublishesReplanRequest() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        IRouteAgentService routeService = mock(IRouteAgentService.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        RouteEventListener listener = new RouteEventListener(mapper, routeService, kafkaTemplate);
        UUID correlationId = UUID.randomUUID();
        String message = mapper.writeValueAsString(mapper.createObjectNode()
                .put("eventId", UUID.randomUUID().toString())
                .put("eventType", "alert.validated")
                .put("occurredAt", Instant.now().toString())
                .put("correlationId", correlationId.toString())
                .put("version", 1)
                .put("producer", "community-alerts")
                .set("payload", mapper.createObjectNode()
                        .put("alertId", 17)
                        .put("activeRoute", true)
                        .put("routeId", "route-42")
                        .put("roadCorridor", "Bogota-Cali")
                        .put("severity", "ALTA")));

        listener.consume(message);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("truckcar.events.route-requests"), eq("route-42"), captor.capture());
        assertThat(captor.getValue().toString()).contains("route.replan.requested");
    }

    @Test
    void corridorsAreComparedAndPublishedInCanonicalUpperCase() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        RouteEventListener listener = new RouteEventListener(mapper, mock(IRouteAgentService.class), kafkaTemplate);
        String message = mapper.writeValueAsString(mapper.createObjectNode()
                .put("eventId", UUID.randomUUID().toString())
                .put("eventType", "alert.validated")
                .put("occurredAt", Instant.now().toString())
                .put("correlationId", UUID.randomUUID().toString())
                .put("version", 1)
                .put("producer", "community-alerts")
                .set("payload", mapper.createObjectNode()
                        .put("alertId", 18)
                        .put("roadCorridor", "Ibagué - Armenia")
                        .put("activeRouteCorridor", "IBAGUE-ARMENIA")
                        .put("severity", "CRITICA")));

        listener.consume(message);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("truckcar.events.route-requests"), eq("IBAGUE-ARMENIA"), captor.capture());
        assertThat(captor.getValue().toString()).contains("affectedCorridor\":\"IBAGUE-ARMENIA");
        assertThat(RouteEventListener.normalizeCorridor("  bogotá-cali ")).isEqualTo("BOGOTA-CALI");
    }
}
