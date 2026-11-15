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
 * @param steps           maniobras paso a paso (actions de HERE con instrucción en español), en orden
 */
public record HereRoute(List<String> labels, double lengthMeters, double durationSeconds, List<double[]> geometry,
                        List<Toll> tolls, List<String> violations, List<Step> steps) {

    /**
     * Una maniobra de HERE.
     *
     * @param action          depart, turn, keep, continue, roundaboutExit, ramp, uTurn, arrive...
     * @param instruction     texto listo para mostrar o leer en voz alta (lang=es-CO)
     * @param direction       left, right, middle; null si no aplica
     * @param severity        light, quite, heavy; null si no aplica
     * @param exit            número de salida en una rotonda; null si no aplica
     * @param lengthMeters    distancia desde esta maniobra hasta la siguiente
     * @param durationSeconds tiempo desde esta maniobra hasta la siguiente
     * @param geometryIndex   índice del punto de la maniobra en {@link #geometry()} (la geometría completa)
     */
    public record Step(String action, String instruction, String direction, String severity, Integer exit,
                       double lengthMeters, double durationSeconds, int geometryIndex) {
    }

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
