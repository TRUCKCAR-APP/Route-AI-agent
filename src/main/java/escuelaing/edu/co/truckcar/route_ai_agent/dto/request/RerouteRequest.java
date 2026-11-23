package escuelaing.edu.co.truckcar.route_ai_agent.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Recálculo durante el viaje: desde la posición actual del camión hasta el destino, con su perfil y las zonas que debe
 * evitar (el tramo con tráfico o un bloqueo). A diferencia de /plan no geocodifica, no consulta alertas ni guarda nada:
 * responde en el tiempo de una sola llamada a HERE.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RerouteRequest {

    @NotNull @DecimalMin("-90") @DecimalMax("90")
    private Double originLatitude;

    @NotNull @DecimalMin("-180") @DecimalMax("180")
    private Double originLongitude;

    @NotNull @DecimalMin("-90") @DecimalMax("90")
    private Double destinationLatitude;

    @NotNull @DecimalMin("-180") @DecimalMax("180")
    private Double destinationLongitude;

    @NotNull(message = "La altura del camión en metros es obligatoria")
    @Positive @DecimalMax("6")
    private Double heightMeters;

    @NotNull(message = "El peso bruto total en toneladas es obligatorio")
    @Positive @DecimalMax("80")
    private Double grossWeightTons;

    /** Opcional: si falta se toma del tipo de camión. */
    @Min(2) @Max(9)
    private Integer axleCount;

    /** Opcional (TRACTOMULA_3S3, MINIMULA, DOBLETROQUE...): da los ejes y si lleva semirremolque. */
    @Size(max = 40)
    private String truckType;

    @Valid
    @Size(max = 10, message = "Como máximo 10 zonas a evitar")
    private List<AvoidPoint> avoid;

    /** Paradas intermedias en orden (restaurante, estación, hotel…): la ruta pasa por ellas antes del destino. */
    @Valid
    @Size(max = 3, message = "Como máximo 3 paradas")
    private List<Waypoint> via;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Waypoint {
        @NotNull @DecimalMin("-90") @DecimalMax("90")
        private Double latitude;

        @NotNull @DecimalMin("-180") @DecimalMax("180")
        private Double longitude;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AvoidPoint {
        @NotNull @DecimalMin("-90") @DecimalMax("90")
        private Double latitude;

        @NotNull @DecimalMin("-180") @DecimalMax("180")
        private Double longitude;

        /** Radio de la zona a evitar; por defecto 300 m. */
        @DecimalMin("50") @DecimalMax("3000")
        private Double radiusMeters;
    }
}
