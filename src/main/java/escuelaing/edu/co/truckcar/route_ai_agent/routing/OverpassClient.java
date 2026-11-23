package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lugares de OpenStreetMap (API Overpass, sin clave) dentro del rectángulo que cubre el tramo de ruta que falta. Es una
 * fuente complementaria de HERE: aporta lo que HERE no distingue (parqueaderos para camiones con hgv=yes, áreas de
 * servicio y de descanso) y negocios de carretera que HERE no tiene. Si Overpass falla, la búsqueda sigue solo con HERE.
 * <p>
 * Los servidores públicos de Overpass se saturan a menudo (502/504): se prueba cada URL configurada en orden, con
 * tiempos cortos, y lo encontrado se guarda unas horas en memoria por tipo y zona.
 */
@Slf4j
@Component
public class OverpassClient {

    static final int MAX_ELEMENTS = 400;

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("fuel", "Estación de servicio"), Map.entry("restaurant", "Restaurante"),
            Map.entry("fast_food", "Comida rápida"), Map.entry("food_court", "Plazoleta de comidas"),
            Map.entry("cafe", "Cafetería"), Map.entry("hotel", "Hotel"), Map.entry("motel", "Motel"),
            Map.entry("guest_house", "Hospedaje"), Map.entry("hostel", "Hospedaje"),
            Map.entry("parking", "Parqueadero para camiones"), Map.entry("services", "Área de servicio"),
            Map.entry("rest_area", "Área de descanso"), Map.entry("toilets", "Baños públicos"),
            Map.entry("tyres", "Montallantas"), Map.entry("car_repair", "Taller"), Map.entry("truck_repair", "Taller de camiones"),
            Map.entry("mechanic", "Taller"), Map.entry("atm", "Cajero automático"), Map.entry("bank", "Banco"),
            Map.entry("pharmacy", "Droguería"), Map.entry("hospital", "Hospital"), Map.entry("clinic", "Clínica"),
            Map.entry("convenience", "Tienda"), Map.entry("supermarket", "Supermercado"));

    static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L;
    static final int CACHE_ENTRIES = 200;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final List<String> urls;
    private final boolean enabled;
    private final Map<String, Cached> cache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > CACHE_ENTRIES;
                }
            });

    private record Cached(long at, List<StopCandidate> places) {
    }

    public OverpassClient(ObjectMapper objectMapper,
                          @Value("${truckcar.places.overpass-urls:https://overpass-api.de/api/interpreter,https://maps.mail.ru/osm/tools/overpass/api/interpreter}") String urls,
                          @Value("${truckcar.places.overpass-enabled:true}") boolean enabled) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(5000);
        this.restClient = RestClient.builder().requestFactory(factory)
                .defaultHeader("User-Agent", "TruckCar/1.0 (route-ai-agent)").build();
        this.objectMapper = objectMapper;
        this.urls = urls == null ? List.of() : java.util.Arrays.stream(urls.split(","))
                .map(String::strip).filter(u -> !u.isEmpty()).toList();
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled && !urls.isEmpty();
    }

    /**
     * bbox = {sur, oeste, norte, este}. Lanza una excepción si ningún servidor de Overpass responde: quien llama la
     * trata como opcional.
     */
    public List<StopCandidate> search(StopCategory category, double[] bbox) {
        String key = category.code() + String.format(Locale.ROOT, ":%.2f,%.2f,%.2f,%.2f", bbox[0], bbox[1], bbox[2], bbox[3]);
        Cached hit = cache.get(key);
        if (hit != null && System.currentTimeMillis() - hit.at() < CACHE_TTL_MS) {
            return hit.places();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("data", query(category, bbox));
        RuntimeException last = null;
        for (String url : urls) {
            try {
                String body = restClient.post().uri(url).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                        .retrieve().body(String.class);
                List<StopCandidate> found = parse(objectMapper.readTree(body == null ? "{}" : body), category);
                cache.put(key, new Cached(System.currentTimeMillis(), found));
                Metrics.counter("truckcar.stops.overpass", "outcome", found.isEmpty() ? "empty" : "found").increment();
                return found;
            } catch (java.io.IOException e) {
                last = new IllegalStateException("Overpass devolvió una respuesta que no es JSON");
            } catch (RuntimeException e) {
                last = e;
            }
        }
        Metrics.counter("truckcar.stops.overpass", "outcome", "unavailable").increment();
        throw last != null ? last : new IllegalStateException("Sin servidores de Overpass configurados");
    }

    static String query(StopCategory category, double[] bbox) {
        String box = String.format(Locale.ROOT, "(%.5f,%.5f,%.5f,%.5f)", bbox[0], bbox[1], bbox[2], bbox[3]);
        StringBuilder q = new StringBuilder("[out:json][timeout:9];(");
        for (String filter : category.overpassFilters()) {
            q.append("nwr").append(filter).append(box).append(';');
        }
        return q.append(");out tags center ").append(MAX_ELEMENTS).append(';').toString();
    }

    static List<StopCandidate> parse(JsonNode body, StopCategory category) {
        List<StopCandidate> out = new ArrayList<>();
        for (JsonNode element : body.path("elements")) {
            JsonNode point = element.has("lat") ? element : element.path("center");
            if (!point.has("lat") || !point.has("lon")) {
                continue;
            }
            JsonNode tags = element.path("tags");
            String kind = firstText(tags, "highway", "amenity", "tourism", "shop", "craft");
            String title = firstText(tags, "name", "brand", "operator");
            boolean area = "rest_area".equals(kind) || "services".equals(kind) || "toilets".equals(kind);
            if (title == null) {
                if (!area || category.unnamedTitle() == null) {
                    continue;
                }
                title = LABELS.getOrDefault(kind, category.unnamedTitle());
            }
            String hgv = tags.path("hgv").asText("");
            boolean truckFriendly = "yes".equals(hgv) || "designated".equals(hgv) || "services".equals(kind)
                    || "rest_area".equals(kind) || StopCategory.TRUCK_NAME.matcher(title).find();
            String hours = tags.path("opening_hours").asText("").strip();
            out.add(new StopCandidate(
                    "osm:" + element.path("type").asText("node") + "/" + element.path("id").asText(),
                    title,
                    address(tags),
                    kind == null ? null : LABELS.getOrDefault(kind, null),
                    point.get("lat").asDouble(),
                    point.get("lon").asDouble(),
                    firstText(tags, "phone", "contact:phone", "contact:mobile"),
                    "24/7".equals(hours) ? Boolean.TRUE : null,
                    truckFriendly,
                    Set.of("OSM")));
        }
        return out;
    }

    private static String address(JsonNode tags) {
        String street = firstText(tags, "addr:street");
        String number = firstText(tags, "addr:housenumber");
        String city = firstText(tags, "addr:city");
        String line = street == null ? "" : street + (number == null ? "" : " # " + number);
        if (city != null) {
            line = line.isEmpty() ? city : line + ", " + city;
        }
        return line.isEmpty() ? null : line;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").strip();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
