package escuelaing.edu.co.truckcar.route_ai_agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /api/v1/routes/plan de punta a punta con PostgreSQL y Kafka reales (Testcontainers) y un servidor falso que
 * hace de HERE (Routing v8 y Geocoding), Routing-Restrictions y Community-Alerts: sin catálogo fijo, la ruta sale de
 * HERE, una restricción registrada descarta la más rápida y la decisión queda en la base y en Kafka.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "AZURE_OPENAI_ENDPOINT=https://example.invalid/",
        "AZURE_OPENAI_KEY=test-key"
})
class HereRoutePlanningIntegrationTest {

    private static final String KEY = "clave-integracion";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("truckcar_db");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    private static HttpServer stub;
    private static volatile int hereStatus = 200;
    private static volatile boolean cityExists = true;
    private static final AtomicInteger validations = new AtomicInteger();
    private static final List<String> hereQueries = new CopyOnWriteArrayList<>();
    private static final List<String> validationBodies = new CopyOnWriteArrayList<>();

    /** Dos rutas con la forma real de HERE; la geometría es el ejemplo de la especificación (cerca de 50.10, 8.69). */
    private static final String HERE_ROUTES = """
            {"routes":[
              {"routeLabels":[{"name":{"value":"45"}}],
               "sections":[{"summary":{"length":480000,"duration":39600},"polyline":"BFoz5xJ67i1B1B7PzIhaxL7Y","tolls":[]}]},
              {"routeLabels":[{"name":{"value":"40"}}],
               "sections":[{"summary":{"length":498540,"duration":42069},"polyline":"BFoz5xJ67i1B1B7PzIhaxL7Y",
                 "tolls":[{"tollSystem":"RUTA 40-COL","fares":[{"convertedPrice":{"currency":"COP","value":70700},
                           "price":{"currency":"COP","value":70700}}],
                           "tollCollectionLocations":[{"name":"Sibaté (Rural)","location":{"lat":50.1006,"lng":8.6915}}]}]}]}
            ]}
            """;

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v8/routes", exchange -> {
            hereQueries.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            respond(exchange, hereStatus, hereStatus == 200 ? HERE_ROUTES : "{\"title\":\"Internal\"}");
        });
        stub.createContext("/v1/geocode", exchange -> {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            boolean bogota = query.contains("q=Bogotá");
            respond(exchange, 200, !cityExists ? "{\"items\":[]}"
                    : "{\"items\":[{\"title\":\"x\",\"position\":{\"lat\":" + (bogota ? "4.61496,\"lng\":-74.06941"
                    : "3.88301,\"lng\":-77.04739") + "}}]}");
        });
        stub.createContext("/api/v1/restrictions/validate-route", exchange -> {
            validationBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // La primera ruta validada (la más rápida, vía 45) choca con un gálibo registrado en TruckCar.
            boolean first = validations.getAndIncrement() % 2 == 0;
            respond(exchange, 200, first
                    ? "{\"passable\":false,\"restrictionsEvaluated\":3,\"blocking\":[{\"kind\":\"BLOCKING\",\"reason\":\"Gálibo de 4,20 m en el Puente La Vega; el camión mide 4,40 m\"}],\"warnings\":[]}"
                    : "{\"passable\":true,\"restrictionsEvaluated\":3,\"blocking\":[],\"warnings\":[]}");
        });
        stub.createContext("/api/v1/alerts", exchange -> respond(exchange, 200, """
                [{"id":1,"alertType":"DERRUMBE","severity":"CRITICA","title":"Derrumbe parcial","municipality":"Fusagasugá",
                  "latitude":50.1003,"longitude":8.6930},
                 {"id":2,"alertType":"RETEN","severity":"MEDIA","title":"Retén","municipality":"Cali",
                  "latitude":3.45,"longitude":-76.53}]
                """));
        stub.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=route_ai_agent,public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("truckcar.routing.here.api-key", () -> KEY);
        registry.add("truckcar.routing.here.router-url", () -> base + "/v8");
        registry.add("truckcar.routing.here.geocode-url", () -> base + "/v1");
        registry.add("truckcar.services.restrictions-url", () -> base);
        registry.add("truckcar.services.alerts-url", () -> base);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reset() {
        hereStatus = 200;
        cityExists = true;
        validations.set(0);
        hereQueries.clear();
        validationBodies.clear();
    }

    private ResponseEntity<JsonNode> plan(String plate) {
        return plan(plate, "Bogotá", "Buenaventura");
    }

    private ResponseEntity<JsonNode> plan(String plate, String origin, String destination) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"vehiclePlate":"%s","truckType":"TRACTOMULA_3S3","grossWeightTons":52,"heightMeters":4.4,
                 "originCity":"%s","destinationCity":"%s"}""".formatted(plate, origin, destination);
        return rest.postForEntity("/api/v1/routes/plan", new HttpEntity<>(body, headers), JsonNode.class);
    }

    @Test
    void plansWithHereDiscardsTheRouteBlockedByARegisteredRestrictionAndRecordsTheDecision() throws Exception {
        String plate = "IT" + (System.nanoTime() % 10_000);

        ResponseEntity<JsonNode> response = plan(plate);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode plan = response.getBody();
        assertThat(plan.get("provider").asText()).isEqualTo("HERE");
        assertThat(plan.get("recommendedCorridor").asText()).isEqualTo("Bogotá → Buenaventura por la vía 40");
        assertThat(plan.get("totalDistanceKm").asDouble()).isEqualTo(498.5);
        assertThat(plan.get("tollsCount").asInt()).isEqualTo(1);
        assertThat(plan.get("tollCostCop").asDouble()).isEqualTo(70_700);
        assertThat(plan.get("geometry")).hasSize(4);
        assertThat(plan.get("restrictionsVerified").asBoolean()).isTrue();
        // Derrumbe crítico a ~100 m de la ruta; el retén de Cali no cuenta.
        assertThat(plan.get("activeAlertsCount").asInt()).isEqualTo(1);
        assertThat(plan.get("riskLevel").asText()).isEqualTo("ALTO");
        JsonNode rejected = plan.get("alternatives").get(0);
        assertThat(rejected.get("isPassable").asBoolean()).isFalse();
        assertThat(rejected.get("issues").get(0).asText()).contains("Gálibo de 4,20 m");
        assertThat(plan.get("aiRecommendation").asText()).contains("vía 40", "$70.700");

        // HERE recibió el perfil del camión y la clave; las ciudades se geocodificaron (Bogotá: 4.61496,-74.06941).
        assertThat(hereQueries.get(0)).contains("transportMode=truck", "origin=4.614960,-74.069410",
                "vehicle[height]=440", "vehicle[grossWeight]=52000", "vehicle[axleCount]=6", "apiKey=" + KEY);
        JsonNode validation = mapper.readTree(validationBodies.get(0));
        assertThat(validation.get("heightMeters").asDouble()).isEqualTo(4.4);
        assertThat(validation.get("path").get(0).get(0).asDouble()).isEqualTo(50.10228);

        long planId = plan.get("planId").asLong();
        assertThat(jdbc.queryForObject("SELECT selected_corridor FROM route_ai_agent.route_decision_logs WHERE id = ?",
                String.class, planId)).isEqualTo("Bogotá → Buenaventura por la vía 40");
        JsonNode event = awaitPlanEvent(planId);
        assertThat(event.path("payload").path("chosenCorridor").asText()).isEqualTo("Bogotá → Buenaventura por la vía 40");
        assertThat(event.path("payload").path("riskLevel").asText()).isEqualTo("ALTO");
    }

    @Test
    void withHereDownThePlanFailsWith503AndNothingIsInvented() {
        hereStatus = 500;
        String plate = "DOWN" + (System.nanoTime() % 10_000);

        ResponseEntity<JsonNode> response = plan(plate);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("message").asText()).doesNotContain(KEY);
        assertThat(hereQueries).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM route_ai_agent.route_decision_logs WHERE vehicle_plate = ?",
                Integer.class, plate)).isZero();
    }

    @Test
    void anUnknownCityWithoutCoordinatesIs422() {
        cityExists = false;

        // Ciudades que no están en la caché de geocodificación (las conocidas se recuerdan entre peticiones).
        ResponseEntity<JsonNode> response = plan("NOCITY", "Villa Inexistente", "Pueblo Inventado");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("message").asText()).contains("No se encontró la ciudad de origen");
        assertThat(hereQueries).isEmpty();
    }

    private JsonNode awaitPlanEvent(long planId) throws Exception {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("truckcar.events.route-plans"));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode envelope = mapper.readTree(record.value());
                    if ("route.plan.generated".equals(envelope.path("eventType").asText())
                            && envelope.path("payload").path("planId").asLong() == planId) {
                        return envelope;
                    }
                }
            }
        }
        throw new AssertionError("No llegó route.plan.generated del plan #" + planId);
    }
}
