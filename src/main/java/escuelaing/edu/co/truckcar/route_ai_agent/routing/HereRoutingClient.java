package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RoutePlanningException;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RoutingProviderUnavailableException;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Cliente de HERE Routing v8 con transportMode=truck: la ruta respeta la altura, el peso bruto, el peso por eje y los
 * ejes del camión, y trae los peajes con su costo en pesos (currency=COP). Pide la principal y hasta
 * {@link HereProperties#getAlternatives()} alternativas.
 * <p>
 * Un 5xx o un timeout se reintenta una vez (HERE responde a veces 503 "Timeout processing request"). Sin clave, con la
 * clave rechazada o caído, lanza {@link RoutingProviderUnavailableException} (503): nunca se inventa una ruta.
 * <p>
 * HERE solo acepta la clave en la URL (apiKey=...). Por eso este cliente no usa el RestClient instrumentado de Spring
 * (sus trazas guardan la URL completa y la exportarían al collector) y ningún mensaje de error incluye la URL.
 */
@Slf4j
@Component
public class HereRoutingClient {

    private final RestClient restClient;
    private final HereProperties properties;
    private final ObjectMapper objectMapper;

    public HereRoutingClient(HereProperties properties, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public record GeoPoint(double latitude, double longitude) {
    }

    /**
     * Zona que la ruta debe evitar (HERE avoid[areas]): un cuadrado de lado 2 × radiusMeters centrado en el punto.
     */
    public record AvoidArea(double latitude, double longitude, double radiusMeters) {
    }

    public List<HereRoute> routes(GeoPoint origin, GeoPoint destination, TruckProfile truck) {
        return routes(origin, destination, truck, List.of());
    }

    public List<HereRoute> routes(GeoPoint origin, GeoPoint destination, TruckProfile truck, List<AvoidArea> avoid) {
        return routes(origin, List.of(), destination, truck, avoid);
    }

    /** Rutas con paradas intermedias (via) en orden: la geometría y los pasos cubren todos los tramos. */
    public List<HereRoute> routes(GeoPoint origin, List<GeoPoint> via, GeoPoint destination, TruckProfile truck,
                                  List<AvoidArea> avoid) {
        requireKey();
        URI uri = buildUri(origin, via, destination, truck, avoid, FULL_RETURN, properties.getAlternatives());
        JsonNode body = callWithOneRetry(uri);
        List<HereRoute> routes = new ArrayList<>();
        for (JsonNode route : body.path("routes")) {
            routes.add(parseRoute(route));
        }
        Metrics.counter("truckcar.routing.here.requests", "outcome", routes.isEmpty() ? "no_route" : "ok").increment();
        return routes;
    }

    URI buildUri(GeoPoint origin, GeoPoint destination, TruckProfile truck) {
        return buildUri(origin, destination, truck, List.of());
    }

    URI buildUri(GeoPoint origin, GeoPoint destination, TruckProfile truck, List<AvoidArea> avoid) {
        return buildUri(origin, List.of(), destination, truck, avoid, FULL_RETURN, properties.getAlternatives());
    }

    static final String FULL_RETURN = "summary,polyline,tolls,routeLabels,actions,instructions";

    /**
     * Viaje con paradas, solo con el resumen de cada tramo (sin geometría ni maniobras): sirve para medir el desvío de
     * pasar por un lugar. Vacío si HERE no encuentra ruta para el camión.
     */
    public Optional<Trip> trip(GeoPoint origin, List<GeoPoint> via, GeoPoint destination, TruckProfile truck) {
        requireKey();
        JsonNode body = callWithOneRetry(buildUri(origin, via, destination, truck, List.of(), "summary", 0));
        JsonNode route = body.path("routes").path(0);
        if (route.isMissingNode()) {
            return Optional.empty();
        }
        List<double[]> legs = new ArrayList<>();
        for (JsonNode section : route.path("sections")) {
            legs.add(new double[]{section.path("summary").path("length").asDouble(0),
                    section.path("summary").path("duration").asDouble(0)});
        }
        HereRoute parsed = parseRoute(route);
        return Optional.of(new Trip(parsed.lengthMeters(), parsed.durationSeconds(), legs, parsed.violatesTruckRestrictions()));
    }

    /**
     * Resumen de un viaje: total, cada tramo [metros, segundos] (el primero llega a la primera parada) y si HERE marca
     * alguna restricción del camión que no se pudo evitar.
     */
    public record Trip(double lengthMeters, double durationSeconds, List<double[]> legs, boolean violatesTruckRestrictions) {
    }

    private void requireKey() {
        if (!properties.hasApiKey()) {
            throw new RoutingProviderUnavailableException(
                    "El planeador de rutas no tiene configurada la clave de HERE (HERE_API_KEY)");
        }
    }

    URI buildUri(GeoPoint origin, List<GeoPoint> via, GeoPoint destination, TruckProfile truck, List<AvoidArea> avoid,
                 String returnFields, int alternatives) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(properties.getRouterUrl().replaceAll("/+$", "") + "/routes")
                .queryParam("transportMode", "truck")
                .queryParam("origin", coordinate(origin));
        for (GeoPoint stop : via == null ? List.<GeoPoint>of() : via) {
            uri.queryParam("via", coordinate(stop));
        }
        uri.queryParam("destination", coordinate(destination))
                .queryParam("return", returnFields)
                .queryParam("alternatives", alternatives)
                .queryParam("currency", "COP")
                .queryParam("lang", "es-CO")
                .queryParam("vehicle[height]", truck.heightCm())
                .queryParam("vehicle[grossWeight]", truck.grossWeightKg());
        if (truck.axleCount() != null) {
            uri.queryParam("vehicle[axleCount]", truck.axleCount());
            uri.queryParam("vehicle[weightPerAxle]", truck.weightPerAxleKg());
        }
        if (truck.tractor()) {
            uri.queryParam("vehicle[type]", "tractor");
            uri.queryParam("vehicle[trailerCount]", 1);
        }
        if (avoid != null && !avoid.isEmpty()) {
            uri.queryParam("avoid[areas]", avoid.stream().map(HereRoutingClient::bbox).collect(java.util.stream.Collectors.joining("|")));
        }
        uri.queryParam("apiKey", properties.getApiKey());
        return uri.encode().build().toUri();
    }

    private JsonNode callWithOneRetry(URI uri) {
        String lastProblem = "sin respuesta";
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String body = restClient.get().uri(uri).retrieve().body(String.class);
                return objectMapper.readTree(body == null ? "{}" : body);
            } catch (HttpClientErrorException e) {
                Metrics.counter("truckcar.routing.here.requests", "outcome", "client_error").increment();
                int status = e.getStatusCode().value();
                if (status == 401 || status == 403) {
                    throw new RoutingProviderUnavailableException("HERE rechazó la clave configurada (HTTP " + status + ")");
                }
                if (status == 429) {
                    throw new RoutingProviderUnavailableException("Se agotó la cuota de HERE (HTTP 429)");
                }
                throw new RoutePlanningException("HERE no pudo calcular la ruta: " + hereMessage(e.getResponseBodyAsString()));
            } catch (HttpServerErrorException e) {
                lastProblem = "HTTP " + e.getStatusCode().value();
                log.warn("HERE respondió {} (intento {} de 2)", lastProblem, attempt);
            } catch (ResourceAccessException e) {
                // El mensaje de ResourceAccessException incluye la URL (con la clave): solo se registra el tipo.
                lastProblem = e.getCause() != null ? e.getCause().getClass().getSimpleName() : "sin respuesta";
                log.warn("HERE no respondió: {} (intento {} de 2)", lastProblem, attempt);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new RoutingProviderUnavailableException("HERE devolvió una respuesta que no es JSON");
            }
        }
        Metrics.counter("truckcar.routing.here.requests", "outcome", "unavailable").increment();
        throw new RoutingProviderUnavailableException("HERE no está disponible en este momento (" + lastProblem + ")");
    }

    private String hereMessage(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            String title = json.path("title").asText("");
            String cause = json.path("cause").asText("");
            String message = (title + " " + cause).trim();
            return message.isEmpty() ? "solicitud rechazada" : message;
        } catch (Exception e) {
            return "solicitud rechazada";
        }
    }

    private HereRoute parseRoute(JsonNode route) {
        double length = 0;
        double duration = 0;
        List<double[]> geometry = new ArrayList<>();
        List<HereRoute.Toll> tolls = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        List<HereRoute.Step> steps = new ArrayList<>();
        for (JsonNode section : route.path("sections")) {
            length += section.path("summary").path("length").asDouble(0);
            duration += section.path("summary").path("duration").asDouble(0);
            List<double[]> points = FlexiblePolyline.decode(section.path("polyline").asText(""));
            // Las secciones comparten el punto de unión: no se repite. El offset de una acción es el índice del punto
            // dentro de su sección; en la geometría completa, la sección empieza en el último punto ya agregado.
            int sectionStart = geometry.isEmpty() ? 0 : geometry.size() - 1;
            geometry.addAll(geometry.isEmpty() || points.isEmpty() ? points : points.subList(1, points.size()));
            int lastIndex = Math.max(0, geometry.size() - 1);
            for (JsonNode action : section.path("actions")) {
                int index = Math.min(lastIndex, sectionStart + Math.max(0, action.path("offset").asInt(0)));
                steps.add(new HereRoute.Step(
                        action.path("action").asText("continue"),
                        action.path("instruction").asText(""),
                        textOrNull(action, "direction"),
                        textOrNull(action, "severity"),
                        action.path("exit").canConvertToInt() ? action.path("exit").asInt() : null,
                        action.path("length").asDouble(0),
                        action.path("duration").asDouble(0),
                        index));
            }
            for (JsonNode toll : section.path("tolls")) {
                tolls.add(parseToll(toll));
            }
            for (JsonNode notice : section.path("notices")) {
                String code = notice.path("code").asText("");
                if (code.toLowerCase(Locale.ROOT).startsWith("violated")) {
                    violations.add(violationText(notice));
                }
            }
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode label : route.path("routeLabels")) {
            String name = label.path("name").path("value").asText("");
            if (!name.isBlank()) {
                labels.add(name);
            }
        }
        return new HereRoute(labels, length, duration, geometry, tolls, violations.stream().distinct().toList(), steps);
    }

    /** bbox:oeste,sur,este,norte con 5 decimales (≈1 m). */
    static String bbox(AvoidArea area) {
        double dLat = area.radiusMeters() / 111_320d;
        double dLng = area.radiusMeters() / (111_320d * Math.max(0.01, Math.cos(Math.toRadians(area.latitude()))));
        return String.format(Locale.ROOT, "bbox:%.5f,%.5f,%.5f,%.5f", area.longitude() - dLng, area.latitude() - dLat,
                area.longitude() + dLng, area.latitude() + dLat);
    }

    private static String textOrNull(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private static HereRoute.Toll parseToll(JsonNode toll) {
        JsonNode location = toll.path("tollCollectionLocations").path(0);
        String name = location.path("name").asText(toll.path("tollSystem").asText("Peaje"));
        JsonNode fare = toll.path("fares").path(0);
        JsonNode price = fare.has("convertedPrice") ? fare.path("convertedPrice") : fare.path("price");
        return new HereRoute.Toll(
                name.isBlank() ? "Peaje" : name,
                location.path("location").has("lat") ? location.path("location").path("lat").asDouble() : null,
                location.path("location").has("lng") ? location.path("location").path("lng").asDouble() : null,
                price.has("value") ? price.path("value").asDouble() : null,
                price.path("currency").asText(null));
    }

    private static String violationText(JsonNode notice) {
        List<String> causes = new ArrayList<>();
        for (JsonNode detail : notice.path("details")) {
            String cause = detail.path("cause").asText("");
            if (!cause.isBlank()) {
                causes.add(cause);
            }
        }
        return causes.isEmpty() ? notice.path("title").asText("Viola una restricción del vehículo") : String.join("; ", causes);
    }

    private static String coordinate(GeoPoint point) {
        return String.format(Locale.ROOT, "%.6f,%.6f", point.latitude(), point.longitude());
    }
}
