package escuelaing.edu.co.truckcar.route_ai_agent.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Peaje en la ruta, con la tarifa de HERE para el camión (en pesos colombianos). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TollResponse {
    private String name;
    private Double latitude;
    private Double longitude;
    private Double cost;
    private String currency;
}
