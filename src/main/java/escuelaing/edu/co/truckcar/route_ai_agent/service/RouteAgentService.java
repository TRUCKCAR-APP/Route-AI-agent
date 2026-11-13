package escuelaing.edu.co.truckcar.route_ai_agent.service;

import escuelaing.edu.co.truckcar.route_ai_agent.dto.client.AlertSummaryDto;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.client.RouteValidationDto;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.event.EventEnvelope;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.event.RoutePlanGeneratedEvent;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.PlanRouteRequest;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.RoutePlanResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RoutePlanningException;
import escuelaing.edu.co.truckcar.route_ai_agent.model.RouteDecisionLog;
import escuelaing.edu.co.truckcar.route_ai_agent.repository.RouteDecisionLogRepository;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.GeoMath;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereGeocodingClient;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoute;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient.GeoPoint;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.TruckProfile;
import escuelaing.edu.co.truckcar.route_ai_agent.service.agent.TruckRoutePlanner;
import escuelaing.edu.co.truckcar.route_ai_agent.service.agent.TruckRoutePlanner.EvaluatedRoute;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Planeación de rutas para carga pesada:
 * <ol>
 *   <li>Origen y destino: las coordenadas de la petición o, si no vienen, las de la ciudad (HERE Geocoding).</li>
 *   <li>HERE Routing v8 con perfil de camión (altura, peso bruto, peso por eje y ejes): ruta principal y
 *       alternativas, con sus peajes en pesos.</li>
 *   <li>Cada ruta se valida contra las restricciones registradas en Routing-Restrictions (validate-route, por
 *       geometría): gálibos, límites de peso y cierres que el mapa de HERE no conoce.</li>
 *   <li>Las alertas activas de Community-Alerts cuentan solo si están cerca de la ruta.</li>
 * </ol>
 * Si HERE no responde, la planeación falla con 503: ya no hay rutas de catálogo ni rutas inventadas. Si
 * Routing-Restrictions o Community-Alerts no responden, la ruta sale igual con la advertencia correspondiente.
 */
@Slf4j
@Service
public class RouteAgentService implements IRouteAgentService {

    /** Corredor alrededor de la ruta en el que una restricción registrada la afecta (máximo de Routing-Restrictions: 500). */
    static final double RESTRICTION_CORRIDOR_METERS = 60;
    private static final int MAX_VALIDATION_POINTS = 2_000;

    private final TruckRoutePlanner planner;
    private final HereRoutingClient hereRouting;
    private final HereGeocodingClient hereGeocoding;
    private final RouteDecisionLogRepository repository;
    private final RestClient restClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String restrictionsServiceUrl;
    private final String alertsServiceUrl;
    private final String routePlansTopic;

    public RouteAgentService(TruckRoutePlanner planner, HereRoutingClient hereRouting, HereGeocodingClient hereGeocoding,
                             RouteDecisionLogRepository repository, RestClient restClient,
                             KafkaTemplate<String, Object> kafkaTemplate,
                             @Value("${truckcar.services.restrictions-url:http://localhost:8084}") String restrictionsServiceUrl,
                             @Value("${truckcar.services.alerts-url:http://localhost:8082}") String alertsServiceUrl,
                             @Value("${truckcar.kafka.topic.route-decisions:truckcar.events.route-plans}") String routePlansTopic) {
        this.planner = planner;
        this.hereRouting = hereRouting;
        this.hereGeocoding = hereGeocoding;
        this.repository = repository;
        this.restClient = restClient;
        this.kafkaTemplate = kafkaTemplate;
        this.restrictionsServiceUrl = restrictionsServiceUrl;
        this.alertsServiceUrl = alertsServiceUrl;
        this.routePlansTopic = routePlansTopic;
    }

    /**
     * Sin transacción alrededor de las llamadas externas (HERE puede tardar segundos): el registro de la decisión se
     * guarda al final, en su propia transacción.
     */
    @Override
    public RoutePlanResponse planOptimalRoute(PlanRouteRequest request) {
        GeoPoint origin = locate(request.getOriginLatitude(), request.getOriginLongitude(), request.getOriginCity(), "origen");
        GeoPoint destination = locate(request.getDestinationLatitude(), request.getDestinationLongitude(),
                request.getDestinationCity(), "destino");
        TruckProfile truck = TruckProfile.from(request);

        List<HereRoute> routes = hereRouting.routes(origin, destination, truck);
        if (routes.isEmpty()) {
            throw new RoutePlanningException("HERE no encontró una ruta para camión entre " + request.getOriginCity()
                    + " y " + request.getDestinationCity());
        }
        List<EvaluatedRoute> evaluated = routes.stream().map(route -> validateWithRestrictions(route, truck, request)).toList();
        List<AlertSummaryDto> alerts = fetchActiveAlerts();

        RoutePlanResponse plan = planner.plan(request, truck, evaluated, alerts);
        // La misma elección del planeador: la primera apta o, si ninguna lo es, la primera.
        int restrictionsEvaluated = evaluated.stream().filter(EvaluatedRoute::passable).findFirst()
                .orElse(evaluated.get(0)).restrictionsEvaluated();

        RouteDecisionLog saved = repository.save(RouteDecisionLog.builder()
                .vehiclePlate(plan.getVehiclePlate())
                .originCity(plan.getOriginCity())
                .destinationCity(plan.getDestinationCity())
                .selectedCorridor(plan.getRecommendedCorridor())
                .totalDistanceKm(plan.getTotalDistanceKm())
                .estimatedDurationHours(plan.getEstimatedDurationHours())
                .riskLevel(plan.getRiskLevel())
                .activeAlertsCount(plan.getActiveAlertsCount())
                .restrictionsEvaluatedCount(restrictionsEvaluated)
                .agentRecommendation(truncate(plan.getAiRecommendation(), 1500))
                .createdAt(LocalDateTime.now())
                .build());
        plan.setPlanId(saved.getId());
        publishRoutePlanKafkaEvent(saved);
        log.info("Plan #{} {} -> {}: {} km, riesgo {}, {} peajes", saved.getId(), plan.getOriginCity(),
                plan.getDestinationCity(), plan.getTotalDistanceKm(), plan.getRiskLevel(), plan.getTollsCount());
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

    /** Las coordenadas de la petición si son válidas; si no, las de la ciudad. Sin ninguna de las dos, 422. */
    private GeoPoint locate(Double latitude, Double longitude, String city, String role) {
        if (latitude != null && longitude != null && Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180 && !(latitude == 0 && longitude == 0)) {
            return new GeoPoint(latitude, longitude);
        }
        return hereGeocoding.city(city).orElseThrow(() -> new RoutePlanningException(
                "No se encontró la ciudad de " + role + " '" + city + "' en Colombia; envíe sus coordenadas"));
    }

    private EvaluatedRoute validateWithRestrictions(HereRoute route, TruckProfile truck, PlanRouteRequest request) {
        if (route.geometry().size() < 2) {
            return new EvaluatedRoute(route, List.of(), List.of(), 0, false);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("vehiclePlate", request.getVehiclePlate());
        body.put("heightMeters", truck.heightMeters());
        body.put("grossWeightTons", truck.grossWeightTons());
        body.put("axleWeightTons", truck.weightPerAxleTons());
        body.put("corridorMeters", RESTRICTION_CORRIDOR_METERS);
        body.put("path", GeoMath.toLatLngList(GeoMath.simplify(route.geometry(), MAX_VALIDATION_POINTS)));
        try {
            RouteValidationDto result = restClient.post()
                    .uri(restrictionsServiceUrl + "/api/v1/restrictions/validate-route")
                    .body(body)
                    .retrieve()
                    .body(RouteValidationDto.class);
            if (result == null) {
                return new EvaluatedRoute(route, List.of(), List.of(), 0, false);
            }
            return new EvaluatedRoute(route, reasons(result.getBlocking()), reasons(result.getWarnings()),
                    result.getRestrictionsEvaluated() == null ? 0 : result.getRestrictionsEvaluated(), true);
        } catch (Exception e) {
            log.warn("Routing-Restrictions no validó la ruta ({}): {}", restrictionsServiceUrl, e.getMessage());
            return new EvaluatedRoute(route, List.of(), List.of(), 0, false);
        }
    }

    private static List<String> reasons(List<RouteValidationDto.Hit> hits) {
        List<String> reasons = new ArrayList<>();
        if (hits != null) {
            for (RouteValidationDto.Hit hit : hits) {
                if (hit.getReason() != null && !hit.getReason().isBlank()) {
                    reasons.add(hit.getReason());
                }
            }
        }
        return reasons;
    }

    private List<AlertSummaryDto> fetchActiveAlerts() {
        try {
            // Community-Alerts expone las alertas activas en GET /api/v1/alerts.
            List<AlertSummaryDto> alerts = restClient.get()
                    .uri(alertsServiceUrl + "/api/v1/alerts")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<AlertSummaryDto>>() {});
            return alerts == null ? List.of() : alerts;
        } catch (Exception e) {
            log.warn("Community-Alerts no respondió ({}); la ruta se planea sin alertas: {}", alertsServiceUrl, e.getMessage());
            return List.of();
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
                    .correlationId(UUID.nameUUIDFromBytes(String.valueOf(logEntry.getId()).getBytes(StandardCharsets.UTF_8)))
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

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
