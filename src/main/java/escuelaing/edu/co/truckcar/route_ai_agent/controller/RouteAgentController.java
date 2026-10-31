package escuelaing.edu.co.truckcar.route_ai_agent.controller;

import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.PlanRouteRequest;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.RoutePlanResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RouteAccessDeniedException;
import escuelaing.edu.co.truckcar.route_ai_agent.service.IRouteAgentService;
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

    @PostMapping("/plan")
    public ResponseEntity<RoutePlanResponse> planRoute(@Valid @RequestBody PlanRouteRequest request) {
        return ResponseEntity.ok(service.planOptimalRoute(request));
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