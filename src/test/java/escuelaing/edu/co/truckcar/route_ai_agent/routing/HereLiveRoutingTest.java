package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient.GeoPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contra HERE de verdad: solo corre si HERE_API_KEY está definida (localmente, desde .env; en el CI no se define, así
 * que se omite y no gasta cuota).
 */
@EnabledIfEnvironmentVariable(named = "HERE_API_KEY", matches = ".+")
class HereLiveRoutingTest {

    private final HereRoutingClient routing;
    private final HereGeocodingClient geocoding;

    HereLiveRoutingTest() {
        HereProperties properties = new HereProperties();
        properties.setApiKey(System.getenv("HERE_API_KEY"));
        routing = new HereRoutingClient(properties, new ObjectMapper());
        geocoding = new HereGeocodingClient(properties);
    }

    @Test
    void aTractomulaFromBogotaToBuenaventuraGetsATruckRouteWithTollsInPesos() {
        GeoPoint bogota = geocoding.city("Bogotá D.C.").orElseThrow();
        GeoPoint buenaventura = geocoding.city("Buenaventura").orElseThrow();

        List<HereRoute> routes = routing.routes(bogota, buenaventura, new TruckProfile(440, 52_000, 6, 8_667, true));

        assertThat(routes).isNotEmpty();
        HereRoute best = routes.get(0);
        assertThat(best.violations()).isEmpty();
        assertThat(best.lengthMeters() / 1000).isBetween(400.0, 650.0);
        assertThat(best.geometry().size()).isGreaterThan(100);
        assertThat(best.tolls()).isNotEmpty();
        assertThat(best.tolls().get(0).currency()).isEqualTo("COP");
        assertThat(best.tollCost()).isGreaterThan(0);
    }

    @Test
    void aTruckTallerThanTheNationalLimitIsReportedAsAViolation() {
        GeoPoint bogota = new GeoPoint(4.6097, -74.0817);
        GeoPoint ibague = new GeoPoint(4.4389, -75.2322);

        List<HereRoute> routes = routing.routes(bogota, ibague, new TruckProfile(600, 52_000, 6, 8_667, true));

        assertThat(routes).isNotEmpty();
        assertThat(routes.get(0).violations()).anyMatch(v -> v.contains("height"));
    }

    @Test
    void anUnknownCityIsNotFound() {
        assertThat(geocoding.city("Ciudad Inventada XYZ")).isEmpty();
    }
}
