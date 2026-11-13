package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import com.fasterxml.jackson.databind.JsonNode;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RoutingProviderUnavailableException;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient.GeoPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordenadas de una ciudad colombiana con HERE Geocoding v1, para las peticiones que solo traen el nombre (originCity
 * o destinationCity sin coordenadas). Las ciudades encontradas se guardan en memoria: no cambian de lugar.
 */
@Slf4j
@Component
public class HereGeocodingClient {

    private final RestClient restClient;
    private final HereProperties properties;
    private final Map<String, GeoPoint> cache = new ConcurrentHashMap<>();

    /** Sin el RestClient instrumentado de Spring: la URL lleva la clave de HERE (ver HereRoutingClient). */
    public HereGeocodingClient(HereProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.properties = properties;
    }

    /** Vacío si HERE no conoce la ciudad. Sin clave o con HERE caído lanza RoutingProviderUnavailableException. */
    public Optional<GeoPoint> city(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = normalize(name);
        GeoPoint cached = cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (!properties.hasApiKey()) {
            throw new RoutingProviderUnavailableException(
                    "El planeador de rutas no tiene configurada la clave de HERE (HERE_API_KEY)");
        }
        try {
            JsonNode body = restClient.get()
                    .uri(UriComponentsBuilder.fromHttpUrl(properties.getGeocodeUrl().replaceAll("/+$", "") + "/geocode")
                            .queryParam("q", name.trim())
                            .queryParam("in", "countryCode:COL")
                            .queryParam("types", "city")
                            .queryParam("limit", 1)
                            .queryParam("apiKey", properties.getApiKey())
                            .encode().build().toUri())
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode position = body == null ? null : body.path("items").path(0).path("position");
            if (position == null || !position.has("lat") || !position.has("lng")) {
                return Optional.empty();
            }
            GeoPoint point = new GeoPoint(position.get("lat").asDouble(), position.get("lng").asDouble());
            cache.put(key, point);
            return Optional.of(point);
        } catch (HttpClientErrorException.BadRequest e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            throw new RoutingProviderUnavailableException(
                    "HERE rechazó la consulta de la ciudad (HTTP " + e.getStatusCode().value() + ")");
        } catch (RuntimeException e) {
            // Sin la excepción original: su mensaje puede incluir la URL con la clave.
            throw new RoutingProviderUnavailableException("HERE no está disponible para ubicar la ciudad ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    private static String normalize(String name) {
        return Normalizer.normalize(name.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ");
    }
}
