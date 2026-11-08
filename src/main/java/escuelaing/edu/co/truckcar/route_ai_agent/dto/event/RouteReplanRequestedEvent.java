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
    /**
     * Dónde está el bloqueo, qué dice la alerta y quién lo detectó. La PWA del conductor usa la posición para saber si
     * el bloqueo cae sobre lo que le falta de ruta (las alertas del teléfono no traen corredor con nombre).
     */
    private Double latitude;
    private Double longitude;
    private String alertType;
    private String title;
    private String description;
    /** Texto conciso para lectura por voz (TTS) dirigido al conductor. */
    private String voiceText;
    /** Confianza del modelo (0 a 1); null si el replanteo viene de la confirmación de la comunidad. */
    private Double confidence;
    /** AZURE_OPENAI, BEDROCK, RULES o COMMUNITY. */
    private String source;
}
