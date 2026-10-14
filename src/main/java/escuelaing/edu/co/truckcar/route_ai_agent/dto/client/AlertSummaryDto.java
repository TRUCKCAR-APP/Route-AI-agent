package escuelaing.edu.co.truckcar.route_ai_agent.dto.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlertSummaryDto {
    private Long id;
    private String alertType;
    private String severity;
    private String title;
    private String description;
    private String department;
    private String municipality;
    private Double latitude;
    private Double longitude;
}