package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import com.sun.net.httpserver.HttpServer;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.PlaceResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RoutingProviderUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Búsqueda de destinos contra un HERE falso con la forma real de /discover. */
class HerePlacesClientTest {

    static final String DISCOVER = """
            {"items":[
              {"title":"Escuela Colombiana de Ingeniería Julio Garavito","id":"here:pds:place:1",
               "address":{"label":"Escuela Colombiana de Ingeniería Julio Garavito, AK 45 #205-59, Bogotá, Colombia"},
               "position":{"lat":4.78305,"lng":-74.04257},"access":[{"lat":4.78312,"lng":-74.04221}],
               "distance":14230,"categories":[{"id":"800-8200-0174","name":"Universidad","primary":true}]},
              {"title":"Escuela Colombiana de Carreras Industriales","id":"here:pds:place:2",
               "address":{"label":"Calle 72 #13-23, Bogotá, Colombia"},
               "position":{"lat":4.65681,"lng":-74.06103},"distance":2100},
              {"title":"","position":{"lat":1,"lng":1}},
              {"title":"Sin posición"}
            ]}
            """;

    private HttpServer server;
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private int status = 200;
    private HereProperties properties;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/discover", exchange -> {
            lastQuery.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            byte[] bytes = (status == 200 ? DISCOVER : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        properties = new HereProperties();
        properties.setApiKey("clave-de-prueba");
        properties.setSearchUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void searchesNearTheDriverInsideColombiaInSpanish() {
        List<PlaceResponse> places = new HerePlacesClient(properties).search("escuela colombiana de ingeniería", 4.70, -74.05, 5);
        assertThat(lastQuery.get()).contains("q=escuela colombiana de ingeniería", "at=4.700000,-74.050000",
                "in=countryCode:COL", "limit=5", "lang=es-CO");
        assertThat(places).hasSize(2);
        PlaceResponse first = places.get(0);
        assertThat(first.title()).isEqualTo("Escuela Colombiana de Ingeniería Julio Garavito");
        assertThat(first.category()).isEqualTo("Universidad");
        assertThat(first.address()).contains("AK 45 #205-59");
        assertThat(first.latitude()).as("llega a la entrada vehicular (access), no al centro del predio").isEqualTo(4.78312);
        assertThat(first.distanceMeters()).isEqualTo(14230);
        assertThat(places.get(1).category()).isNull();
    }

    @Test
    void withoutPositionItSearchesFromBogota() {
        new HerePlacesClient(properties).search("Tunja", null, null, 3);
        assertThat(lastQuery.get()).contains("at=4.651000,-74.081700");
    }

    @Test
    void withoutKeyOrWithHereDownIsUnavailable() {
        status = 503;
        assertThatThrownBy(() -> new HerePlacesClient(properties).search("Tunja", null, null, 3))
                .isInstanceOf(RoutingProviderUnavailableException.class);
        properties.setApiKey("");
        assertThatThrownBy(() -> new HerePlacesClient(properties).search("Tunja", null, null, 3))
                .isInstanceOf(RoutingProviderUnavailableException.class);
    }
}
