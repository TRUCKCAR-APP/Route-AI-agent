package escuelaing.edu.co.truckcar.route_ai_agent.controller;

import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.StopSearchRequest;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.StopSearchResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.service.StopSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/routes/stops: paradas del tipo pedido sobre lo que falta de la ruta (restaurante, estación, hotel,
 * descanso…), con el desvío real para el camión. Lo usa el Copiloto de voz cuando el conductor dice «quiero comer».
 * 503 si HERE y OpenStreetMap no responden.
 */
@RestController
@RequestMapping("/api/v1/routes")
@RequiredArgsConstructor
public class StopSearchController {

    private final StopSearchService stops;

    @PostMapping("/stops")
    public ResponseEntity<StopSearchResponse> search(@Valid @RequestBody StopSearchRequest request) {
        return ResponseEntity.ok(stops.search(request));
    }
}
