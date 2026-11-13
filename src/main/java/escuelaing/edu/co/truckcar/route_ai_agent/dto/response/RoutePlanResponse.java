package escuelaing.edu.co.truckcar.route_ai_agent.dto.response;

import escuelaing.edu.co.truckcar.route_ai_agent.model.RouteRiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutePlanResponse {
    private Long planId;
    private String vehiclePlate;
    private String originCity;
    private String destinationCity;
    private String recommendedCorridor;
    private Double totalDistanceKm;
    private Double estimatedDurationHours;
    private RouteRiskLevel riskLevel;
    private Integer activeAlertsCount;
    private List<RouteLegResponse> segments;
    private List<String> warnings;
    private List<AlternativeRouteResponse> alternatives;
    /** Texto armado con los datos reales de la ruta (proveedor, restricciones verificadas, peajes y alertas). */
    private String aiRecommendation;
    private LocalDateTime generatedAt;

    /** Proveedor de la ruta (HERE, perfil de camión). */
    private String provider;
    /** Geometría de la ruta recomendada: [[latitud, longitud], ...], simplificada a 2000 puntos como máximo. */
    private List<List<Double>> geometry;
    /** Peajes en orden de paso, con la tarifa para el camión. */
    private List<TollResponse> tolls;
    private Integer tollsCount;
    /** Suma de las tarifas de los peajes, en pesos colombianos. */
    private Double tollCostCop;
    /** false si Routing-Restrictions no respondió y las restricciones registradas no se pudieron verificar. */
    private Boolean restrictionsVerified;
}
