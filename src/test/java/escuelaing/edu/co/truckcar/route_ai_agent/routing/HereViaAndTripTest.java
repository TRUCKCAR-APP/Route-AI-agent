package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient.GeoPoint;
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

/** Paradas intermedias (via) y el resumen por tramos con que se mide el desvío, contra un HERE falso. */
class HereViaAndTripTest {

    /** Dos tramos: hasta la parada (12 km, 15 min) y de la parada al destino (80 km, 100 min). */
    static final String TRIP_JSON = """
            {"routes":[{"id":"r1","sections":[
              {"type":"vehicle","summary":{"length":12000,"duration":900}},
              {"type":"vehicle","summary":{"length":80000,"duration":6000}}]}]}
            """;

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>(TRIP_JSON);
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private HereProperties properties;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v8/routes", exchange -> {
            lastQuery.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        properties = new HereProperties();
        properties.setApiKey("clave-de-prueba");
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
    void theStopGoesAsViaBetweenOriginAndDestinationAndTheRouteCoversBothLegs() {
        body.set(HereRoutingClientTest.ROUTES_JSON);
        client().routes(new GeoPoint(4.71, -74.07), List.of(new GeoPoint(4.80, -74.20)), new GeoPoint(5.01, -74.47),
                mula(), List.of());
        String q = lastQuery.get();
        assertThat(q).contains("origin=4.710000,-74.070000", "via=4.800000,-74.200000", "destination=5.010000,-74.470000");
        assertThat(q.indexOf("origin=")).isLessThan(q.indexOf("via=")).isLessThan(q.indexOf("destination="));
    }

    @Test
    void theTripSummaryGivesEachLegSoTheAppKnowsHowFarTheStopIsAndHowMuchItAdds() {
        HereRoutingClient.Trip trip = client().trip(new GeoPoint(4.71, -74.07), List.of(new GeoPoint(4.80, -74.20)),
                new GeoPoint(5.01, -74.47), mula()).orElseThrow();
        assertThat(lastQuery.get()).contains("return=summary", "alternatives=0", "vehicle[height]=440");
        assertThat(trip.lengthMeters()).isEqualTo(92000);
        assertThat(trip.durationSeconds()).isEqualTo(6900);
        assertThat(trip.legs()).hasSize(2);
        assertThat(trip.legs().get(0)).containsExactly(12000, 900);
        assertThat(trip.violatesTruckRestrictions()).isFalse();
    }

    @Test
    void withoutARouteForTheTruckTheTripIsEmpty() {
        body.set("{\"routes\":[]}");
        assertThat(client().trip(new GeoPoint(4.71, -74.07), List.of(), new GeoPoint(5.01, -74.47), mula())).isEmpty();
    }

    @Test
    void aTripThatBreaksATruckRestrictionIsMarked() {
        body.set("""
                {"routes":[{"sections":[{"summary":{"length":1000,"duration":60},
                  "notices":[{"code":"violatedVehicleRestriction","details":[{"cause":"height limit of 420 cm"}]}]}]}]}
                """);
        assertThat(client().trip(new GeoPoint(4.71, -74.07), List.of(new GeoPoint(4.8, -74.2)),
                new GeoPoint(5.01, -74.47), mula()).orElseThrow().violatesTruckRestrictions()).isTrue();
    }
}
