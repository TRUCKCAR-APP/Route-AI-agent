package escuelaing.edu.co.truckcar.route_ai_agent.dto.response;

import java.util.List;

/**
 * Paradas propuestas sobre la ruta, de la mejor a la peor (las que se alcanzan antes y desvían menos primero).
 *
 * @param category        tipo pedido (fuel, restaurant, rest…)
 * @param aheadMeters     cuánto de la ruta se revisó hacia adelante
 * @param maxDetourMeters desvío máximo aceptado
 * @param sources         fuentes que respondieron (HERE, OSM)
 * @param stops           hasta limit paradas; vacía si no hay ninguna que cumpla
 */
public record StopSearchResponse(String category, int aheadMeters, int maxDetourMeters, List<String> sources,
                                 List<Stop> stops) {

    /**
     * @param distanceMeters  por carretera desde el camión hasta el lugar
     * @param secondsToStop   tiempo estimado hasta el lugar
     * @param detourMeters    metros que suma al viaje pasar por el lugar (ir, parar y volver a la ruta)
     * @param detourSeconds   segundos que suma al viaje (sin contar el tiempo de la parada)
     * @param openNow         true/false si HERE sabe si está abierto ahora; null si no se sabe
     * @param truckFriendly   la fuente o el nombre dicen que recibe camiones
     * @param sources         HERE, OSM o ambas
     */
    public record Stop(String id, String title, String address, String category, double latitude, double longitude,
                       double distanceMeters, double secondsToStop, double detourMeters, double detourSeconds,
                       String phone, Boolean openNow, boolean truckFriendly, List<String> sources) {
    }
}
