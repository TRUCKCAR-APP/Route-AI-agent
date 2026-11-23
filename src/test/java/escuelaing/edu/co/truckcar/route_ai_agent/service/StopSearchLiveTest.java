package escuelaing.edu.co.truckcar.route_ai_agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.StopSearchRequest;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.StopSearchResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HerePlacesClient;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereProperties;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoute;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient.GeoPoint;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.OverpassClient;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.TruckProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contra HERE y OpenStreetMap de verdad, en la ruta Bogotá → Villeta de una tractomula: solo corre si HERE_API_KEY
 * está definida (localmente, desde .env); en el CI se omite.
 */
@EnabledIfEnvironmentVariable(named = "HERE_API_KEY", matches = ".+")
class StopSearchLiveTest {

    @Test
    void restaurantsAndRestPlacesOnTheWayToVilletaAddAtMostFiveKilometres() {
        HereProperties properties = new HereProperties();
        properties.setApiKey(System.getenv("HERE_API_KEY"));
        ObjectMapper mapper = new ObjectMapper();
        HereRoutingClient routing = new HereRoutingClient(properties, mapper);
        StopSearchService service = new StopSearchService(new HerePlacesClient(properties),
                new OverpassClient(mapper, "https://overpass-api.de/api/interpreter,https://maps.mail.ru/osm/tools/overpass/api/interpreter", true), routing);

        GeoPoint funza = new GeoPoint(4.7163, -74.2110);
        GeoPoint villeta = new GeoPoint(5.0136, -74.4728);
        HereRoute route = routing.routes(funza, villeta, new TruckProfile(420, 32_000, 5, 6_400, true)).get(0);
        List<List<Double>> ahead = route.geometry().stream().map(p -> List.of(p[0], p[1])).toList();

        for (String category : List.of("restaurant", "rest", "fuel")) {
            StopSearchResponse result = service.search(StopSearchRequest.builder()
                    .latitude(funza.latitude()).longitude(funza.longitude())
                    .destinationLatitude(villeta.latitude()).destinationLongitude(villeta.longitude())
                    .ahead(ahead).category(category).heightMeters(4.2).grossWeightTons(32.0).truckType("TRACTOMULA_2S3")
                    .build());
            System.out.println("\n" + category + " (fuentes " + result.sources() + "):");
            result.stops().forEach(s -> System.out.printf("  %s | %.1f km, %d min | +%.1f km, +%d min | %s%s%s%n",
                    s.title(), s.distanceMeters() / 1000, Math.round(s.secondsToStop() / 60), s.detourMeters() / 1000,
                    Math.round(s.detourSeconds() / 60), s.sources(), s.truckFriendly() ? " | camiones" : "",
                    s.openNow() == null ? "" : s.openNow() ? " | abierto" : " | cerrado"));
            assertThat(result.stops()).isNotEmpty();
            assertThat(result.stops()).allSatisfy(s -> assertThat(s.detourMeters()).isLessThanOrEqualTo(5000));
        }
    }
}
