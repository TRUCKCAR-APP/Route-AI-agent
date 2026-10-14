package escuelaing.edu.co.truckcar.route_ai_agent.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteReplanRequestedEvent {
    private Long alertId;
    private String routeId;
    private String affectedCorridor;
    private String severity;
    private String reason;
}
