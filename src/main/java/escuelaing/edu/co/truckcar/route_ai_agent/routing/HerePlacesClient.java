package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import com.fasterxml.jackson.databind.JsonNode;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.PlaceResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RoutingProviderUnavailableException;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Busca lugares por nombre con HERE Geocoding &amp; Search v7 (/discover), cerca de la posición del conductor y dentro de
 * Colombia: «Escuela Colombiana de Ingeniería», «bodega Éxito Funza», «Calle 80 con Boyacá».
 * <p>
 * Sin el RestClient instrumentado de Spring: la URL lleva la clave de HERE (igual que HereGeocodingClient).
 */
@Slf4j
@Component
public class HerePlacesClient {

    /** Centro de Bogotá: si el conductor no tiene posición, HERE necesita un punto de referencia. */
    static final double DEFAULT_LAT = 4.6510;
    static final double DEFAULT_LNG = -74.0817;

    private final RestClient restClient;
    private final HereProperties properties;

    public HerePlacesClient(HereProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(Math.min(properties.getReadTimeoutMs(), 8000));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.properties = properties;
    }

    public List<PlaceResponse> search(String query, Double latitude, Double longitude, int limit) {
        if (!properties.hasApiKey()) {
            throw new RoutingProviderUnavailableException("La búsqueda de destinos no tiene configurada la clave de HERE (HERE_API_KEY)");
        }
        double lat = latitude == null ? DEFAULT_LAT : latitude;
        double lng = longitude == null ? DEFAULT_LNG : longitude;
        try {
            JsonNode body = restClient.get()
                    .uri(UriComponentsBuilder.fromHttpUrl(properties.getSearchUrl().replaceAll("/+$", "") + "/discover")
                            .queryParam("q", query.strip())
                            .queryParam("at", String.format(Locale.ROOT, "%.6f,%.6f", lat, lng))
                            .queryParam("in", "countryCode:COL")
                            .queryParam("limit", limit)
                            .queryParam("lang", "es-CO")
                            .queryParam("apiKey", properties.getApiKey())
                            .encode().build().toUri())
                    .retrieve()
                    .body(JsonNode.class);
            List<PlaceResponse> places = parse(body);
            Metrics.counter("truckcar.places.searches", "outcome", places.isEmpty() ? "empty" : "found").increment();
            return places;
        } catch (HttpClientErrorException.BadRequest e) {
            return List.of();
        } catch (HttpClientErrorException e) {
            throw new RoutingProviderUnavailableException(
                    "HERE rechazó la búsqueda del destino (HTTP " + e.getStatusCode().value() + ")");
        } catch (RuntimeException e) {
            // Sin la excepción original: su mensaje puede incluir la URL con la clave.
            throw new RoutingProviderUnavailableException("HERE no está disponible para buscar el destino ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    static List<PlaceResponse> parse(JsonNode body) {
        List<PlaceResponse> places = new ArrayList<>();
        if (body == null) return places;
        for (JsonNode item : body.path("items")) {
            JsonNode position = item.has("access") && item.path("access").size() > 0
                    ? item.path("access").path(0) : item.path("position");
            if (!position.has("lat") || !position.has("lng")) continue;
            String title = item.path("title").asText("").strip();
            if (title.isEmpty()) continue;
            String address = item.path("address").path("label").asText("").strip();
            String category = item.path("categories").path(0).path("name").asText(null);
            places.add(new PlaceResponse(
                    item.path("id").asText(null),
                    title,
                    address.isEmpty() || address.equals(title) ? null : address,
                    category,
                    position.get("lat").asDouble(),
                    position.get("lng").asDouble(),
                    item.has("distance") ? item.get("distance").asDouble() : null));
        }
        return places;
    }
}
