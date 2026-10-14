package escuelaing.edu.co.truckcar.route_ai_agent.dto.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventEnvelopeTest {

    @Test
    void serializesStandardRouteEnvelope() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        EventEnvelope envelope = EventEnvelope.builder()
                .eventId(eventId)
                .eventType("route.plan.generated")
                .occurredAt(Instant.parse("2026-10-10T23:00:00Z"))
                .correlationId(correlationId)
                .version(1)
                .producer("route-ai-agent")
                .payload(mapper.createObjectNode().put("planId", 42L))
                .build();

        EventEnvelope restored = mapper.readValue(mapper.writeValueAsString(envelope), EventEnvelope.class);

        assertThat(restored.getEventId()).isEqualTo(eventId);
        assertThat(restored.getEventType()).isEqualTo("route.plan.generated");
        assertThat(restored.getOccurredAt()).isEqualTo(Instant.parse("2026-10-10T23:00:00Z"));
        assertThat(restored.getCorrelationId()).isEqualTo(correlationId);
        assertThat(restored.getPayload().path("planId").asLong()).isEqualTo(42L);
    }
}
