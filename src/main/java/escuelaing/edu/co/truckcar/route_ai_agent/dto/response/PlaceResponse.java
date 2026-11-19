package escuelaing.edu.co.truckcar.route_ai_agent.dto.response;

/**
 * Un destino encontrado por nombre.
 *
 * @param id             identificador de HERE (para volver a pedirlo)
 * @param title          nombre del lugar, p. ej. «Escuela Colombiana de Ingeniería Julio Garavito»
 * @param address        dirección completa; null si es igual al nombre
 * @param category       tipo de lugar según HERE (universidad, bodega, estación de servicio…); null si no tiene
 * @param latitude       punto de llegada (la entrada vehicular si HERE la conoce)
 * @param longitude      punto de llegada
 * @param distanceMeters distancia en línea recta desde la posición del conductor; null si no se envió posición
 */
public record PlaceResponse(String id, String title, String address, String category, double latitude, double longitude,
                            Double distanceMeters) {
}
