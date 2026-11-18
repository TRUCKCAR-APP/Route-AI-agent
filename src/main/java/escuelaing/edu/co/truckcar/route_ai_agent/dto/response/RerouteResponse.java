package escuelaing.edu.co.truckcar.route_ai_agent.dto.response;

import java.util.List;

/**
 * Alternativas para el camión desde su posición actual, en el orden de HERE (la primera es la recomendada). La
 * duración incluye el tráfico: HERE calcula con la hora de salida actual.
 *
 * @param provider siempre HERE
 * @param routes   rutas; primero las que no violan restricciones del camión
 */
public record RerouteResponse(String provider, List<Option> routes) {

    /**
     * @param distanceMeters  largo total
     * @param durationSeconds tiempo estimado con tráfico
     * @param geometry        puntos [latitud, longitud]
     * @param steps           maniobras en español
     * @param violations      restricciones del camión que la ruta no cumple; vacía si es apta
     * @param tollCost        suma de los peajes (COP si HERE lo convirtió); 0 sin peajes con tarifa
     */
    public record Option(double distanceMeters, double durationSeconds, List<double[]> geometry,
                         List<RouteStepResponse> steps, List<String> violations, double tollCost) {
    }
}
