package escuelaing.edu.co.truckcar.route_ai_agent.dto.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Respuesta de Routing-Restrictions POST /api/v1/restrictions/validate-route: restricciones registradas (gálibos,
 * límites de peso, cierres) a menos de corridorMeters de la geometría de la ruta.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteValidationDto {

    private Boolean passable;
    private Integer restrictionsEvaluated;
    private List<Hit> blocking;
    private List<Hit> warnings;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Hit {
        private String kind;
        private String reason;
        private Double alongRouteMeters;
    }
}
