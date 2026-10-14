package escuelaing.edu.co.truckcar.route_ai_agent.service;

import escuelaing.edu.co.truckcar.route_ai_agent.dto.client.AlertSummaryDto;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.client.RouteCompatibilityDto;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.event.RoutePlanGeneratedEvent;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.event.EventEnvelope;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.PlanRouteRequest;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.RoutePlanResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RoutePlanningException;
import escuelaing.edu.co.truckcar.route_ai_agent.model.RouteDecisionLog;
import escuelaing.edu.co.truckcar.route_ai_agent.repository.RouteDecisionLogRepository;
import escuelaing.edu.co.truckcar.route_ai_agent.service.agent.RouteOptimizationAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouteAgentService implements IRouteAgentService {

    private final RouteOptimizationAgent agent;
    private final RouteDecisionLogRepository repository;
    private final RestClient restClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${truckcar.services.restrictions-url:http://localhost:8084}")
    private String restrictionsServiceUrl;

    @Value("${truckcar.services.alerts-url:http://localhost:8082}")
    private String alertsServiceUrl;

    @Value("${truckcar.kafka.topic.route-decisions:truckcar.events.route-plans}")
    private String routePlansTopic;

    @Override
    @Transactional
    public RoutePlanResponse planOptimalRoute(PlanRouteRequest request) {
        RouteCompatibilityDto compatibility = validateCompatibilityWithRestrictionsService(request, request.getOriginCity() + " - " + request.getDestinationCity());
        List<AlertSummaryDto> alerts = fetchActiveAlertsFromCommunityAlerts();

        RoutePlanResponse plan = agent.synthesizeOptimalRoute(request, alerts, compatibility);

        RouteDecisionLog logEntity = RouteDecisionLog.builder()
                .vehiclePlate(plan.getVehiclePlate())
                .originCity(plan.getOriginCity())
                .destinationCity(plan.getDestinationCity())
                .selectedCorridor(plan.getRecommendedCorridor())
                .totalDistanceKm(plan.getTotalDistanceKm())
                .estimatedDurationHours(plan.getEstimatedDurationHours())
                .riskLevel(plan.getRiskLevel())
                .activeAlertsCount(plan.getActiveAlertsCount())
                .restrictionsEvaluatedCount(compatibility != null && compatibility.getBlockingReasons() != null ? compatibility.getBlockingReasons().size() : 0)
                .agentRecommendation(plan.getAiRecommendation())
                .createdAt(LocalDateTime.now())
                .build();

        RouteDecisionLog saved = repository.save(logEntity);
        plan.setPlanId(saved.getId());

        publishRoutePlanKafkaEvent(saved);

        return plan;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoutePlanResponse> getHistoryByPlate(String vehiclePlate) {
        return repository.findByVehiclePlateOrderByCreatedAtDesc(vehiclePlate).stream()
                .map(d -> RoutePlanResponse.builder()
                        .planId(d.getId())
                        .vehiclePlate(d.getVehiclePlate())
                        .originCity(d.getOriginCity())
                        .destinationCity(d.getDestinationCity())
                        .recommendedCorridor(d.getSelectedCorridor())
                        .totalDistanceKm(d.getTotalDistanceKm())
                        .estimatedDurationHours(d.getEstimatedDurationHours())
                        .riskLevel(d.getRiskLevel())
                        .activeAlertsCount(d.getActiveAlertsCount())
                        .aiRecommendation(d.getAgentRecommendation())
                        .generatedAt(d.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private RouteCompatibilityDto validateCompatibilityWithRestrictionsService(PlanRouteRequest req, String corridor) {
        try {
            return restClient.post()
                    .uri(restrictionsServiceUrl + "/api/v1/restrictions/validate-compatibility")
                    .body(Map.of(
                            "vehiclePlate", req.getVehiclePlate(),
                            "roadCorridor", corridor,
                            "grossWeightTons", req.getGrossWeightTons(),
                            "heightMeters", req.getHeightMeters(),
                            "axleWeightTons", req.getGrossWeightTons() / 3.0
                    ))
                    .retrieve()
                    .body(RouteCompatibilityDto.class);
        } catch (Exception e) {
            log.error("No se pudo validar compatibilidad con ms-routing-restrictions en {}", restrictionsServiceUrl, e);
            throw new RoutePlanningException("La validación de restricciones no está disponible; no se puede planificar la ruta", e);
        }
    }

    private List<AlertSummaryDto> fetchActiveAlertsFromCommunityAlerts() {
        try {
            return restClient.get()
                    // Community-Alerts expone las alertas activas en GET /api/v1/alerts.
                    .uri(alertsServiceUrl + "/api/v1/alerts")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<AlertSummaryDto>>() {});
        } catch (Exception e) {
            log.error("No se pudo obtener alertas de ms-community-alerts en {}", alertsServiceUrl, e);
            throw new RoutePlanningException("Las alertas viales no están disponibles; no se puede planificar la ruta", e);
        }
    }

    private void publishRoutePlanKafkaEvent(RouteDecisionLog logEntry) {
        try {
            RoutePlanGeneratedEvent payload = RoutePlanGeneratedEvent.builder()
                    .planId(logEntry.getId())
                    .vehiclePlate(logEntry.getVehiclePlate())
                    .originCity(logEntry.getOriginCity())
                    .destinationCity(logEntry.getDestinationCity())
                    .chosenCorridor(logEntry.getSelectedCorridor())
                    .totalDistanceKm(logEntry.getTotalDistanceKm())
                    .durationHours(logEntry.getEstimatedDurationHours())
                    .riskLevel(logEntry.getRiskLevel().name())
                    .build();

            EventEnvelope event = EventEnvelope.builder()
                    .eventId(UUID.randomUUID())
                    .eventType("route.plan.generated")
                    .occurredAt(Instant.now())
                    .correlationId(UUID.nameUUIDFromBytes(
                            String.valueOf(logEntry.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .version(1)
                    .producer("route-ai-agent")
                    .payload(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(payload))
                    .build();

            kafkaTemplate.send(routePlansTopic, logEntry.getVehiclePlate(), event);
        } catch (RuntimeException e) {
            log.error("No se pudo emitir evento de plan de ruta a Kafka", e);
            throw e;
        }
    }
}