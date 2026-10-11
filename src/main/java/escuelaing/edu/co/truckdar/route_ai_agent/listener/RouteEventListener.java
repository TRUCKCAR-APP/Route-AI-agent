package escuelaing.edu.co.truckdar.route_ai_agent.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckdar.route_ai_agent.dto.event.EventEnvelope;
import escuelaing.edu.co.truckdar.route_ai_agent.dto.event.RouteReplanRequestedEvent;
import escuelaing.edu.co.truckdar.route_ai_agent.dto.request.PlanRouteRequest;
import escuelaing.edu.co.truckdar.route_ai_agent.service.IRouteAgentService;
import escuelaing.edu.co.truckdar.route_ai_agent.service.AzureOpenAiAlertInterpreterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RouteEventListener {

    private final ObjectMapper objectMapper;
    private final IRouteAgentService routeAgentService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AzureOpenAiAlertInterpreterService alertInterpreter;

    @Value("${truckdar.kafka.topic.route-events:truckdar.events.route-requests}")
    private String routeEventsTopic = "truckdar.events.route-requests";

    public RouteEventListener(ObjectMapper objectMapper, IRouteAgentService routeAgentService,
                              KafkaTemplate<String, Object> kafkaTemplate) {
        this(objectMapper, routeAgentService, kafkaTemplate, null);
    }

    @Autowired
    public RouteEventListener(ObjectMapper objectMapper, IRouteAgentService routeAgentService,
                              KafkaTemplate<String, Object> kafkaTemplate,
                              AzureOpenAiAlertInterpreterService alertInterpreter) {
        this.objectMapper = objectMapper;
        this.routeAgentService = routeAgentService;
        this.kafkaTemplate = kafkaTemplate;
        this.alertInterpreter = alertInterpreter;
    }

    @KafkaListener(
            topics = {
                    "${truckdar.kafka.topic.route-events:truckdar.events.route-requests}",
                    "${truckdar.kafka.topic.road-alerts:truckdar.events.road-alerts}",
                    "${truckdar.kafka.topic.restrictions:truckdar.events.road-restrictions}"
            },
            groupId = "${spring.kafka.consumer.group-id:route-ai-agent-group}"
    )
    public void consume(String message) {
        try {
            EventEnvelope event = objectMapper.readValue(message, EventEnvelope.class);
            if (event.getEventType() == null || event.getPayload() == null) {
                throw new IllegalArgumentException("El sobre de ruta debe incluir eventType y payload");
            }

            if ("route.replan.requested".equals(event.getEventType())) {
                // Las solicitudes emitidas por alertas solo traen el corredor afectado, no los datos del vehículo.
                if (!event.getPayload().hasNonNull("vehiclePlate")) {
                    log.info("Replanteo por corredor '{}' registrado sin datos de vehículo; queda para consumidores aguas abajo: eventId='{}'",
                            text(event.getPayload(), "affectedCorridor"), event.getEventId());
                    return;
                }
                PlanRouteRequest request = objectMapper.treeToValue(event.getPayload(), PlanRouteRequest.class);
                routeAgentService.planOptimalRoute(request);
            } else if ("alert.reported".equals(event.getEventType())) {
                processFreeTextAlert(event);
            } else if ("alert.validated".equals(event.getEventType())) {
                evaluateValidatedAlert(event);
            } else if ("restriction.created".equals(event.getEventType())
                    || "route.updated".equals(event.getEventType())) {
                log.info("Actualización de restricciones recibida para futuros replanteos: eventId='{}', type='{}'",
                        event.getEventId(), event.getEventType());
            } else {
                log.debug("Evento de ruta ignorado: type='{}', eventId='{}'",
                        event.getEventType(), event.getEventId());
            }
        } catch (Exception e) {
            log.error("No se pudo procesar evento de ruta bajo sobre común", e);
            throw new IllegalStateException("Evento de ruta inválido; se requiere retry o DLQ", e);
        }
    }

    private void processFreeTextAlert(EventEnvelope event) {
        String freeText = text(event.getPayload(), "text", "description", "message", "content");
        if (freeText == null) {
            log.info("Alerta reportada sin texto libre interpretable: eventId='{}'", event.getEventId());
            return;
        }
        if (alertInterpreter == null) {
            throw new IllegalStateException("El intérprete Azure OpenAI no está configurado");
        }
        AzureOpenAiAlertInterpreterService.AlertInterpretation interpretation =
                alertInterpreter.interpret(freeText);
        if (!interpretation.isCorridorBlocked()
                || interpretation.affectedCorridor().isBlank()) {
            log.info("Alerta libre no bloquea un corredor; no se solicita replanteo: eventId='{}'",
                    event.getEventId());
            return;
        }
        publishReplan(event, null, interpretation.affectedCorridor(), interpretation.severity(),
                "Azure OpenAI detectó bloqueo del corredor (confianza "
                        + interpretation.confidenceScore() + ")");
    }

    private void evaluateValidatedAlert(EventEnvelope event) {
        JsonNode payload = event.getPayload();
        boolean activeRoute = payload.path("activeRoute").asBoolean(false);
        String routeId = text(payload, "routeId", "activeRouteId");
        String corridor = text(payload, "roadCorridor", "affectedCorridor");
        String activeRouteCorridor = text(payload, "activeRouteCorridor");
        boolean corridorMatches = corridor != null && activeRouteCorridor != null
                && corridor.equalsIgnoreCase(activeRouteCorridor);

        if (!activeRoute && !corridorMatches) {
            log.info("Alerta validada no afecta un trayecto activo; no se solicita replanteo: eventId='{}'",
                    event.getEventId());
            return;
        }
        if (corridor == null || corridor.isBlank()) {
            throw new IllegalArgumentException("alert.validated requiere corredor afectado");
        }

        publishReplan(event, routeId, corridor, text(payload, "severity"),
                "Alerta validada afecta el trayecto activo");
    }

    private void publishReplan(EventEnvelope event, String routeId, String corridor, String severity,
                               String reason) {
        JsonNode payload = event.getPayload();
        RouteReplanRequestedEvent payloadEvent = RouteReplanRequestedEvent.builder()
                .alertId(payload.path("alertId").isNumber() ? payload.path("alertId").asLong() : null)
                .routeId(routeId)
                .affectedCorridor(corridor)
                .severity(severity)
                .reason(reason)
                .build();
        EventEnvelope replanEvent = EventEnvelope.builder()
                .eventId(java.util.UUID.randomUUID())
                .eventType("route.replan.requested")
                .occurredAt(java.time.Instant.now())
                .correlationId(event.getCorrelationId())
                .version(1)
                .producer("route-ai-agent")
                .payload(objectMapper.valueToTree(payloadEvent))
                .build();
        kafkaTemplate.send(routeEventsTopic, routeId == null ? corridor : routeId, replanEvent);
        log.info("Replanteo solicitado por alerta validada en corredor '{}'", corridor);
    }

    private String text(JsonNode payload, String... fields) {
        for (String field : fields) {
            JsonNode value = payload.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }
}
