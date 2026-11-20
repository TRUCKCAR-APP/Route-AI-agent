package escuelaing.edu.co.truckcar.route_ai_agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.RerouteRequest;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.RerouteResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RoutingProviderUnavailableException;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereProperties;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient;
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

/** Recálculo contra un HERE falso (servidor HTTP del JDK). */
class RerouteServiceTest {

    /** La primera ruta de HERE viola la altura del camión; la segunda es apta. */
    static final String ROUTES_JSON = """
            {"routes":[
              {"id":"r1","sections":[{"type":"vehicle","summary":{"length":40000,"duration":3000},
                 "polyline":"BFoz5xJ67i1B1B7PzIhaxL7Y",
                 "notices":[{"title":"Violated vehicle restriction.","code":"violatedVehicleRestriction","severity":"critical",
                   "details":[{"type":"restriction","cause":"Route violates vehicle restriction: height limit of 380 cm"}]}]}]},
              {"id":"r2","sections":[{"type":"vehicle","summary":{"length":52000,"duration":3900},
                 "polyline":"BFoz5xJ67i1B1B7PzIhaxL7Y",
                 "tolls":[{"tollSystem":"ANI","fares":[{"price":{"type":"value","currency":"COP","value":59900}}],
                   "tollCollectionLocations":[{"name":"Peaje Andes","location":{"lat":4.82,"lng":-74.03}}]}],
                 "actions":[
                   {"action":"depart","duration":60,"length":200,"instruction":"Diríjase a Calle 80. Siga durante 200 m.","offset":0},
                   {"action":"turn","duration":120,"length":900,"instruction":"Gire a la derecha en Autopista Norte.","offset":2,"direction":"right","severity":"quite"},
                   {"action":"arrive","duration":0,"length":0,"instruction":"Ha llegado a su destino.","offset":3}]}]}
            ]}
            """;

    private HttpServer server;
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private int status = 200;
    private RerouteService service;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v8/routes", exchange -> {
            lastQuery.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            byte[] bytes = (status == 200 ? ROUTES_JSON : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        HereProperties properties = new HereProperties();
        properties.setApiKey("clave-de-prueba");
        properties.setRouterUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v8/");
        service = new RerouteService(new HereRoutingClient(properties, new ObjectMapper()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static RerouteRequest request(List<RerouteRequest.AvoidPoint> avoid) {
        return RerouteRequest.builder()
                .originLatitude(4.70).originLongitude(-74.05)
                .destinationLatitude(5.53).destinationLongitude(-73.36)
                .heightMeters(4.1).grossWeightTons(32.0).truckType("TRACTOMULA_3S3")
                .avoid(avoid).build();
    }

    @Test
    void asksHereForATruckFromTheCurrentPositionAvoidingTheTrafficArea() {
        service.reroute(request(List.of(RerouteRequest.AvoidPoint.builder().latitude(4.75).longitude(-74.04).build())));
        String query = lastQuery.get();
        assertThat(query).contains("transportMode=truck", "origin=4.700000,-74.050000", "destination=5.530000,-73.360000",
                "vehicle[height]=410", "vehicle[grossWeight]=32000", "vehicle[axleCount]=6", "vehicle[type]=tractor");
        // 300 m por defecto: ≈0,0027° de latitud a cada lado del punto.
        assertThat(query).contains("avoid[areas]=bbox:-74.04270,4.74731,-74.03730,4.75269");
    }

    @Test
    void withoutAreasToAvoidNoAvoidParameterIsSent() {
        service.reroute(request(null));
        assertThat(lastQuery.get()).doesNotContain("avoid[");
    }

    @Test
    void routesFitForTheTruckComeFirstWithSpanishStepsAndTrafficDuration() {
        RerouteResponse response = service.reroute(request(List.of()));
        assertThat(response.provider()).isEqualTo("HERE");
        assertThat(response.routes()).hasSize(2);
        RerouteResponse.Option first = response.routes().get(0);
        assertThat(first.violations()).isEmpty();
        assertThat(first.distanceMeters()).isEqualTo(52000);
        assertThat(first.durationSeconds()).isEqualTo(3900);
        assertThat(first.geometry()).isNotEmpty();
        assertThat(first.steps()).extracting("action").containsExactly("depart", "turn", "arrive");
        assertThat(first.steps().get(1).getInstruction()).isEqualTo("Gire a la derecha en Autopista Norte.");
        assertThat(first.steps().get(1).getDirection()).isEqualTo("right");
        assertThat(response.routes().get(1).violations()).singleElement().asString().contains("380");
    }

    @Test
    void eachTollComesWithItsPositionAndTheFareForThisTruck() {
        RerouteResponse.Option first = service.reroute(request(List.of())).routes().get(0);
        assertThat(first.tolls()).singleElement().satisfies(toll -> {
            assertThat(toll.name()).isEqualTo("Peaje Andes");
            assertThat(toll.latitude()).isEqualTo(4.82);
            assertThat(toll.longitude()).isEqualTo(-74.03);
            assertThat(toll.cost()).isEqualTo(59_900);
            assertThat(toll.currency()).isEqualTo("COP");
        });
        assertThat(first.tollCost()).isEqualTo(59_900);
    }

    @Test
    void hereDownIsAProviderUnavailableErrorSoTheAppUsesItsFallback() {
        status = 503;
        assertThatThrownBy(() -> service.reroute(request(List.of())))
                .isInstanceOf(RoutingProviderUnavailableException.class);
    }

    @Test
    void severalAreasAreJoinedWithAPipe() {
        service.reroute(request(List.of(
                RerouteRequest.AvoidPoint.builder().latitude(4.75).longitude(-74.04).radiusMeters(500.0).build(),
                RerouteRequest.AvoidPoint.builder().latitude(4.80).longitude(-74.02).build())));
        String areas = lastQuery.get().replaceAll(".*avoid\\[areas]=([^&]*).*", "$1");
        assertThat(areas.split("\\|")).hasSize(2).allMatch(a -> a.startsWith("bbox:"));
    }
}
