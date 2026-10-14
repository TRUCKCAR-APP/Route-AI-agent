package escuelaing.edu.co.truckcar.route_ai_agent.exception;

public class RoutePlanningException extends RuntimeException {
    public RoutePlanningException(String message) {
        super(message);
    }

    public RoutePlanningException(String message, Throwable cause) {
        super(message, cause);
    }
}