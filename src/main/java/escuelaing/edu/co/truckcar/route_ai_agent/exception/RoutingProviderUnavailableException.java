package escuelaing.edu.co.truckcar.route_ai_agent.exception;

/**
 * El proveedor de rutas (HERE) no está disponible: sin clave configurada, clave rechazada, caído o sin respuesta.
 * Responde 503: no se inventa una ruta de reemplazo.
 */
public class RoutingProviderUnavailableException extends RuntimeException {

    public RoutingProviderUnavailableException(String message) {
        super(message);
    }

    public RoutingProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
