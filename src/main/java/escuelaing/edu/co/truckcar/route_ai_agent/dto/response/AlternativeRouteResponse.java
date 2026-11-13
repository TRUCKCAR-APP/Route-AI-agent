package escuelaing.edu.co.truckcar.route_ai_agent.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlternativeRouteResponse {
    private String corridorName;
    private Double distanceKm;
    private Double estimatedHours;
    private Boolean isPassable;
    private String riskLevel;
    private List<String> issues;
    private Double tollCostCop;
    /** [[latitud, longitud], ...] simplificada a 500 puntos como máximo, para dibujarla en el mapa. */
    private List<List<Double>> geometry;
}