package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RoutePlanningException;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RoutingProviderUnavailableException;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient.GeoPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contra un HERE falso (servidor HTTP del JDK). */
class HereRoutingClientTest {

    static final String SECRET = "clave-de-prueba-123";

    /** Respuesta mínima con la forma real de HERE Routing v8: 2 rutas, peajes y una violación en la segunda. */
    static final String ROUTES_JSON = """
            {"routes":[
              {"id":"r1","routeLabels":[{"name":{"value":"40","language":"es"}}],
               "sections":[{"type":"vehicle","summary":{"length":498540,"duration":42069},
                 "polyline":"BFoz5xJ67i1B1B7PzIhaxL7Y",
                 "tolls":[{"countryCode":"COL","tollSystem":"RUTA 40-COL",
                   "fares":[{"price":{"type":"value","currency":"COP","value":70700},
                             "convertedPrice":{"type":"value","currency":"COP","value":70700}}],
                   "tollCollectionLocations":[{"name":"Sibaté (Rural)","location":{"lat":50.1006,"lng":8.6915}}]},
                  {"countryCode":"COL","tollSystem":"INVIAS",
                   "fares":[{"price":{"type":"value","currency":"COP","value":40100}}],
                   "tollCollectionLocations":[{"name":"Chinauta","location":{"lat":50.0990,"lng":8.6880}}]}]}]},
              {"id":"r2","routeLabels":[{"name":{"value":"40"}},{"name":{"value":"25"}}],
               "sections":[{"type":"vehicle","summary":{"length":511000,"duration":45000},
                 "polyline":"BFoz5xJ67i1B1B7PzIhaxL7Y",
                 "notices":[{"title":"Violated vehicle restriction.","code":"violatedVehicleRestriction","severity":"critical",
                   "details":[{"type":"restriction","cause":"Route violates vehicle restriction: height limit of 420 cm"}]}]}]}
            ]}
            """;

    private HttpServer server;
    private final Deque<Integer> statuses = new ArrayDeque<>();
    private final AtomicReference<String> body = new AtomicReference<>(ROUTES_JSON);
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private int calls;
    private HereProperties properties;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v8/routes", exchange -> {
            calls++;
            lastQuery.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            int status = statuses.isEmpty() ? 200 : statuses.poll();
            byte[] bytes = (status == 200 ? body.get() : "{\"title\":\"Malformed request\",\"cause\":\"bad origin\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        properties = new HereProperties();
        properties.setApiKey(SECRET);
        properties.setRouterUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v8/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HereRoutingClient client() {
        return new HereRoutingClient(properties, new ObjectMapper());
    }

    private static TruckProfile mula() {
        return new TruckProfile(440, 52_000, 6, 8_667, true);
    }

    @Test
    void sendsTheTruckProfileAndParsesRoutesTollsAndViolations() {
        List<HereRoute> routes = client().routes(new GeoPoint(4.6097, -74.0817), new GeoPoint(3.8801, -77.0312), mula());

        assertThat(lastQuery.get())
                .contains("transportMode=truck", "origin=4.609700,-74.081700", "destination=3.880100,-77.031200",
                        "vehicle[height]=440", "vehicle[grossWeight]=52000", "vehicle[axleCount]=6",
                        "vehicle[weightPerAxle]=8667", "vehicle[type]=tractor", "vehicle[trailerCount]=1",
                        "currency=COP", "return=summary,polyline,tolls,routeLabels", "alternatives=2");
        assertThat(routes).hasSize(2);

        HereRoute first = routes.get(0);
        assertThat(first.labels()).containsExactly("40");
        assertThat(first.lengthMeters()).isEqualTo(498_540);
        assertThat(first.geometry()).hasSize(4);
        assertThat(first.tolls()).extracting(HereRoute.Toll::name).containsExactly("Sibaté (Rural)", "Chinauta");
        assertThat(first.tollCost()).isEqualTo(110_800);
        assertThat(first.tolls().get(0).currency()).isEqualTo("COP");
        assertThat(first.violatesTruckRestrictions()).isFalse();

        assertThat(routes.get(1).violations()).containsExactly("Route violates vehicle restriction: height limit of 420 cm");
    }

    @Test
    void aStraightTruckWithoutKnownAxlesDoesNotSendAxleParameters() {
        client().routes(new GeoPoint(4.6, -74.0), new GeoPoint(4.4, -75.2), new TruckProfile(400, 20_000, null, null, false));

        assertThat(lastQuery.get()).doesNotContain("axleCount", "weightPerAxle", "vehicle[type]");
    }

    @Test
    void aServerErrorIsRetriedOnce() {
        statuses.add(503);

        assertThat(client().routes(new GeoPoint(4.6, -74.0), new GeoPoint(4.4, -75.2), mula())).hasSize(2);
        assertThat(calls).isEqualTo(2);
    }

    @Test
    void twoServerErrorsMeanTheProviderIsUnavailableAndTheKeyNeverAppearsInTheMessage() {
        statuses.add(503);
        statuses.add(500);

        assertThatThrownBy(() -> client().routes(new GeoPoint(4.6, -74.0), new GeoPoint(4.4, -75.2), mula()))
                .isInstanceOf(RoutingProviderUnavailableException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET))
                .hasNoCause();
    }

    @Test
    void aRejectedKeyOrQuotaIsUnavailableAndABadRequestIsAPlanningError() {
        statuses.add(401);
        assertThatThrownBy(() -> client().routes(new GeoPoint(4.6, -74.0), new GeoPoint(4.4, -75.2), mula()))
                .isInstanceOf(RoutingProviderUnavailableException.class).hasMessageContaining("401");

        statuses.add(429);
        assertThatThrownBy(() -> client().routes(new GeoPoint(4.6, -74.0), new GeoPoint(4.4, -75.2), mula()))
                .isInstanceOf(RoutingProviderUnavailableException.class).hasMessageContaining("cuota");

        statuses.add(400);
        assertThatThrownBy(() -> client().routes(new GeoPoint(4.6, -74.0), new GeoPoint(4.4, -75.2), mula()))
                .isInstanceOf(RoutePlanningException.class).hasMessageContaining("Malformed request");
    }

    @Test
    void anUnreachableProviderNeverLeaksTheKey() {
        properties.setRouterUrl("http://127.0.0.1:1/v8");
        properties.setConnectTimeoutMs(300);

        assertThatThrownBy(() -> client().routes(new GeoPoint(4.6, -74.0), new GeoPoint(4.4, -75.2), mula()))
                .isInstanceOf(RoutingProviderUnavailableException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET).doesNotContain("apiKey"));
    }

    @Test
    void withoutAKeyNothingIsCalled() {
        properties.setApiKey(" ");

        assertThatThrownBy(() -> client().routes(new GeoPoint(4.6, -74.0), new GeoPoint(4.4, -75.2), mula()))
                .isInstanceOf(RoutingProviderUnavailableException.class).hasMessageContaining("HERE_API_KEY");
        assertThat(calls).isZero();
    }

    @Test
    void anEmptyRouteListIsReturnedAsIs() {
        body.set("{\"routes\":[]}");

        assertThat(client().routes(new GeoPoint(4.6, -74.0), new GeoPoint(4.4, -75.2), mula())).isEmpty();
    }
}
