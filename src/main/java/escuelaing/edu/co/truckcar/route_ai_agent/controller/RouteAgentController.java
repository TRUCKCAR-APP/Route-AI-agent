package escuelaing.edu.co.truckcar.route_ai_agent.controller;

import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.PlanRouteRequest;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.RerouteRequest;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.PlaceResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.RerouteResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.RoutePlanResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RouteAccessDeniedException;
import escuelaing.edu.co.truckcar.route_ai_agent.service.IRouteAgentService;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HerePlacesClient;
import escuelaing.edu.co.truckcar.route_ai_agent.service.RerouteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/routes")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RouteAgentController {

    private static final String AUTH_ROLE_HEADER = "X-Auth-Role";
    private static final Set<String> CONTROL_TOWER_ROLES = Set.of("COORDINADOR", "ADMIN");

    private final IRouteAgentService service;
    private final RerouteService rerouteService;
    private final HerePlacesClient places;

    @PostMapping("/plan")
    public ResponseEntity<RoutePlanResponse> planRoute(@Valid @RequestBody PlanRouteRequest request) {
        return ResponseEntity.ok(service.planOptimalRoute(request));
    }

    /** Recálculo durante el viaje (perfil de camión y tráfico de HERE), sin guardar historial. */
    @PostMapping("/reroute")
    public ResponseEntity<RerouteResponse> reroute(@Valid @RequestBody RerouteRequest request) {
        return ResponseEntity.ok(rerouteService.reroute(request));
    }

    /**
     * Destinos por nombre para la navegación libre del conductor («¿A dónde va?» o «necesito ir a…» por voz), cerca de
     * su posición y dentro de Colombia. Sin la clave de HERE responde 503.
     */
    @GetMapping("/places")
    public ResponseEntity<List<PlaceResponse>> searchPlaces(
            @RequestParam("q") String query,
            @RequestParam(value = "lat", required = false) Double latitude,
            @RequestParam(value = "lng", required = false) Double longitude,
            @RequestParam(value = "limit", defaultValue = "5") int limit) {
        String q = query == null ? "" : query.strip();
        if (q.length() < 2 || q.length() > 120) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Escriba entre 2 y 120 caracteres para buscar el destino");
        }
        if ((latitude != null && (latitude < -90 || latitude > 90)) || (longitude != null && (longitude < -180 || longitude > 180))) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Posición inválida");
        }
        return ResponseEntity.ok(places.search(q, latitude, longitude, Math.max(1, Math.min(limit, 8))));
    }

    /** El historial por placa revela los recorridos de un vehículo: solo la torre de control. */
    @GetMapping("/history/{plate}")
    public ResponseEntity<List<RoutePlanResponse>> getHistoryByPlate(
            @PathVariable String plate,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        if (authenticatedRole == null || authenticatedRole.isBlank()) {
            throw new RouteAccessDeniedException("Se requiere un rol autenticado para consultar el historial", true);
        }
        if (!CONTROL_TOWER_ROLES.contains(authenticatedRole)) {
            throw new RouteAccessDeniedException("El rol " + authenticatedRole
                    + " no puede consultar el historial de rutas (solo COORDINADOR o ADMIN)", false);
        }
        return ResponseEntity.ok(service.getHistoryByPlate(plate));
    }
}