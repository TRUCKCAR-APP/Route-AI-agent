package escuelaing.edu.co.truckcar.route_ai_agent.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Maniobra paso a paso de la ruta recomendada (HERE Routing v8, return=actions,instructions, lang=es-CO). Reemplaza
 * los pasos que el front pedía al OSRM público con perfil de auto.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteStepResponse {

    /** depart, turn, keep, continue, roundaboutExit, ramp, uTurn, arrive... */
    private String action;
    /** Texto listo para mostrar o leer en voz alta, p. ej. "Gire a la derecha en Avenida Calle 19. Siga durante 617 m." */
    private String instruction;
    /** left, right o middle; null si no aplica. */
    private String direction;
    /** light, quite o heavy; null si no aplica. */
    private String severity;
    /** Número de salida en una rotonda; null si no aplica. */
    private Integer exit;
    /** Punto exacto de la maniobra. */
    private Double latitude;
    private Double longitude;
    /** Distancia desde el origen, a lo largo de la ruta, hasta esta maniobra. */
    private Double distanceFromStartMeters;
    /** Distancia y tiempo desde esta maniobra hasta la siguiente. */
    private Double distanceMeters;
    private Double durationSeconds;
}
