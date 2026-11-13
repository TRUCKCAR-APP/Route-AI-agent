package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * HERE Routing v8 (perfil de camión) y Geocoding v1. La clave solo llega por la variable de entorno HERE_API_KEY
 * (Secret truckcar-secrets/here-api-key en Kubernetes, .env en Docker); nunca va en el repositorio.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "truckcar.routing.here")
public class HereProperties {

    private String apiKey = "";
    private String routerUrl = "https://router.hereapi.com/v8";
    private String geocodeUrl = "https://geocode.search.hereapi.com/v1";
    /** Rutas alternativas además de la principal (HERE admite hasta 6). */
    private int alternatives = 2;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 15000;

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
