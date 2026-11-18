package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.PlanRouteRequest;

import java.util.Locale;
import java.util.Map;

/**
 * Restricciones físicas del camión en las unidades de HERE (centímetros y kilogramos). Los ejes salen del tipo de
 * camión (la misma lista que TruckTypeRequired de Load-Allocation) salvo que la petición los indique.
 *
 * @param heightCm        altura total
 * @param grossWeightKg   peso bruto total (tara + carga)
 * @param axleCount       ejes; null si el tipo no se conoce y la petición no los trae (HERE usa su valor por defecto)
 * @param weightPerAxleKg peso bruto repartido entre los ejes; null sin ejes
 * @param tractor         tractocamión con semirremolque (HERE: vehicle[type]=tractor, un remolque)
 */
public record TruckProfile(int heightCm, int grossWeightKg, Integer axleCount, Integer weightPerAxleKg,
                           boolean tractor) {

    /** Configuración vehicular colombiana (Resolución 4100 de 2004) de cada tipo de camión de TruckCar. */
    private static final Map<String, Integer> AXLES = Map.of(
            "TURBO", 2,
            "SENCILLO", 2,
            "DOBLETROQUE", 3,
            "CUATRO_MANOS", 4,
            "MINIMULA", 4,
            "TRACTOMULA_3S3", 6);

    private static final java.util.Set<String> TRACTORS = java.util.Set.of("MINIMULA", "TRACTOMULA_3S3");

    public static TruckProfile from(PlanRouteRequest request) {
        return of(request.getTruckType(), request.getAxleCount(), request.getHeightMeters(), request.getGrossWeightTons());
    }

    public static TruckProfile of(String truckType, Integer axleCount, double heightMeters, double grossWeightTons) {
        String type = truckType == null ? "" : truckType.trim().toUpperCase(Locale.ROOT);
        Integer axles = axleCount != null && axleCount > 0 ? axleCount : AXLES.get(type);
        int heightCm = (int) Math.round(heightMeters * 100);
        int grossKg = (int) Math.round(grossWeightTons * 1000);
        Integer perAxle = axles == null ? null : (int) Math.round((double) grossKg / axles);
        return new TruckProfile(heightCm, grossKg, axles, perAxle, TRACTORS.contains(type));
    }

    public double heightMeters() {
        return heightCm / 100.0;
    }

    public double grossWeightTons() {
        return grossWeightKg / 1000.0;
    }

    public Double weightPerAxleTons() {
        return weightPerAxleKg == null ? null : weightPerAxleKg / 1000.0;
    }
}
