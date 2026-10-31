package escuelaing.edu.co.truckcar.route_ai_agent.exception;

/** El usuario no puede consultar el historial de rutas: 401 sin rol, 403 si su rol no es de la torre de control. */
public class RouteAccessDeniedException extends RuntimeException {

    private final boolean unauthenticated;

    public RouteAccessDeniedException(String message, boolean unauthenticated) {
        super(message);
        this.unauthenticated = unauthenticated;
    }

    public boolean isUnauthenticated() {
        return unauthenticated;
    }
}
