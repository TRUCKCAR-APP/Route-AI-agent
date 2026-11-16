package escuelaing.edu.co.truckcar.route_ai_agent.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.route_ai_agent.config.JacksonConfig;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.PlanRouteRequest;
import escuelaing.edu.co.truckcar.route_ai_agent.service.AlertInterpreter;
import escuelaing.edu.co.truckcar.route_ai_agent.service.IRouteAgentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Lector tolerante (Bloque 1): con el ObjectMapper de la aplicación, un campo nuevo en el sobre o en el payload de una
 * solicitud de replanteo no manda el evento a la cola de muertos (antes treeToValue a PlanRouteRequest fallaba).
 */
class TolerantRouteEventsTest {

    private final ObjectMapper mapper = new JacksonConfig().objectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void aReplanRequestWithFieldsWeDoNotKnowStillPlansTheRoute() {
        IRouteAgentService service = mock(IRouteAgentService.class);
        RouteEventListener listener = new RouteEventListener(mapper, service, mock(KafkaTemplate.class), mock(AlertInterpreter.class));

        listener.consume("""
                {"eventId":"%s","eventType":"route.replan.requested","occurredAt":"2026-11-16T18:00:00Z","version":1,
                 "producer":"load-allocation","campoFuturoDelSobre":"x",
                 "payload":{"vehiclePlate":"TDK-410","truckType":"TURBO","heightMeters":3.9,"grossWeightTons":12.0,
                            "originCity":"Bogotá","destinationCity":"Tunja","motivoFuturo":"cierre","otro":{"a":1}}}
                """.formatted(UUID.randomUUID()));

        ArgumentCaptor<PlanRouteRequest> request = ArgumentCaptor.forClass(PlanRouteRequest.class);
        verify(service).planOptimalRoute(request.capture());
        assertThat(request.getValue().getVehiclePlate()).isEqualTo("TDK-410");
        assertThat(request.getValue().getDestinationCity()).isEqualTo("Tunja");
    }
}
