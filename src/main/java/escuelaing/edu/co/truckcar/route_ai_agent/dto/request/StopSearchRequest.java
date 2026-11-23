package escuelaing.edu.co.truckcar.route_ai_agent.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Búsqueda de una parada sobre la ruta que el camión ya lleva: lugares del tipo pedido en lo que falta del recorrido,
 * que no lo hagan devolverse y cuyo desvío real (ir, parar y seguir al destino) no pase de maxDetourMeters.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StopSearchRequest {

    /** Posición actual del camión. */
    @NotNull @DecimalMin("-90") @DecimalMax("90")
    private Double latitude;

    @NotNull @DecimalMin("-180") @DecimalMax("180")
    private Double longitude;

    @NotNull @DecimalMin("-90") @DecimalMax("90")
    private Double destinationLatitude;

    @NotNull @DecimalMin("-180") @DecimalMax("180")
    private Double destinationLongitude;

    /** Lo que falta de la ruta guiada, desde la posición actual: [[latitud, longitud], ...]. */
    @NotNull
    @Size(min = 2, max = 5000, message = "La ruta que falta debe tener entre 2 y 5000 puntos")
    private List<@Size(min = 2, max = 2) List<Double>> ahead;

    @NotNull
    @Pattern(regexp = "fuel|restaurant|coffee|hotel|truck_parking|rest|toilet|workshop|store|atm|pharmacy|hospital",
            message = "Tipo de parada no admitido")
    private String category;

    @NotNull(message = "La altura del camión en metros es obligatoria")
    @Positive @DecimalMax("6")
    private Double heightMeters;

    @NotNull(message = "El peso bruto total en toneladas es obligatorio")
    @Positive @DecimalMax("80")
    private Double grossWeightTons;

    @Min(2) @Max(9)
    private Integer axleCount;

    @Size(max = 40)
    private String truckType;

    /** Desvío máximo en metros (ir hasta el lugar y volver a la ruta). Por defecto 5 km. */
    @Min(500) @Max(20000)
    private Integer maxDetourMeters;

    /** Cuánto de la ruta se revisa hacia adelante. Por defecto 60 km. */
    @Min(2000) @Max(200000)
    private Integer aheadMeters;

    @Min(1) @Max(5)
    private Integer limit;
}
