package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import java.util.List;

/**
 * Una ruta de HERE Routing v8 (perfil de camión) ya decodificada.
 *
 * @param labels          números o nombres de las vías principales (routeLabels), p. ej. ["40", "25"]
 * @param lengthMeters    largo total
 * @param durationSeconds duración con tráfico
 * @param geometry        puntos [latitud, longitud] de todas las secciones
 * @param tolls           peajes en orden de paso
 * @param violations      restricciones del camión que la ruta no cumple (avisos violated* de HERE); vacía si es apta
 */
public record HereRoute(List<String> labels, double lengthMeters, double durationSeconds, List<double[]> geometry,
                        List<Toll> tolls, List<String> violations) {

    /** @param cost valor del primer cobro del peaje, en currency (COP si HERE lo convirtió); null si no hay tarifa */
    public record Toll(String name, Double latitude, Double longitude, Double cost, String currency) {
    }

    public double tollCost() {
        return tolls.stream().filter(t -> t.cost() != null).mapToDouble(Toll::cost).sum();
    }

    public boolean violatesTruckRestrictions() {
        return !violations.isEmpty();
    }
}
