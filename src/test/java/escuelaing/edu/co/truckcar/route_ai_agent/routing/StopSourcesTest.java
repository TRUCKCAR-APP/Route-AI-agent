package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Corredor de la ruta (polilínea codificada, proyección) y lectura de las respuestas de HERE y OpenStreetMap. */
class StopSourcesTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theCorridorIsEncodedAsHereExpectsAndDecodesBack() {
        List<double[]> points = List.of(new double[]{50.10228, 8.69821}, new double[]{50.10201, 8.69567},
                new double[]{50.10063, 8.69150}, new double[]{50.09878, 8.68752});
        // Ejemplo oficial de Flexible Polyline (precisión 5, sin tercera dimensión).
        assertThat(FlexiblePolyline.encode(points)).isEqualTo("BFoz5xJ67i1B1B7PzIhaxL7Y");
        List<double[]> colombia = List.of(new double[]{4.71090, -74.07211}, new double[]{5.01616, -74.46868});
        List<double[]> back = FlexiblePolyline.decode(FlexiblePolyline.encode(colombia));
        assertThat(back.get(1)[0]).isEqualTo(5.01616);
        assertThat(back.get(1)[1]).isEqualTo(-74.46868);
    }

    @Test
    void projectionSaysHowFarAlongTheRouteAPlaceIsAndWhetherItWasLeftBehind() {
        // Ruta hacia el norte: 0,01° de latitud ≈ 1.113 m por tramo.
        List<double[]> path = List.of(new double[]{4.70, -74.05}, new double[]{4.71, -74.05}, new double[]{4.72, -74.05});
        double[] cumulative = GeoMath.cumulativeDistances(path);
        GeoMath.Projection ahead = GeoMath.project(4.715, -74.049, path, cumulative);
        assertThat(ahead.alongMeters()).isBetween(1600.0, 1740.0);
        assertThat(ahead.offsetMeters()).isBetween(100.0, 120.0);
        assertThat(ahead.behindStart()).isFalse();
        GeoMath.Projection behind = GeoMath.project(4.69, -74.05, path, cumulative);
        assertThat(behind.behindStart()).isTrue();
        assertThat(GeoMath.head(path, 1500)).hasSize(3);
        assertThat(GeoMath.head(path, 1500).get(2)[0]).isBetween(4.7134, 4.7136);
    }

    @Test
    void hereResultsKeepPhoneOpenNowAndOnlyTruckNamedParkingsCountForTruckParking() throws Exception {
        JsonNode body = json.readTree("""
                {"items":[
                  {"id":"a","title":"Parqueadero","position":{"lat":4.8,"lng":-74.2},
                   "categories":[{"name":"Aparcamiento de coches"}]},
                  {"id":"b","title":"Parador Los Camioneros","position":{"lat":4.81,"lng":-74.21},
                   "access":[{"lat":4.811,"lng":-74.211}],
                   "contacts":[{"phone":[{"value":"+5713334444"}]}],
                   "openingHours":[{"isOpen":true}],"categories":[{"name":"Restaurante"}]}]}
                """);
        List<StopCandidate> parking = HerePlacesClient.parseStops(body, StopCategory.TRUCK_PARKING);
        assertThat(parking).extracting(StopCandidate::title).containsExactly("Parador Los Camioneros");
        StopCandidate parador = parking.get(0);
        assertThat(parador.latitude()).isEqualTo(4.811);
        assertThat(parador.phone()).isEqualTo("+5713334444");
        assertThat(parador.openNow()).isTrue();
        assertThat(parador.truckFriendly()).isTrue();
        assertThat(HerePlacesClient.parseStops(body, StopCategory.RESTAURANT)).hasSize(2);
    }

    @Test
    void openStreetMapGivesTruckParkingsRestAreasAndSkipsUnnamedRestaurants() throws Exception {
        JsonNode body = json.readTree("""
                {"elements":[
                  {"type":"node","id":1,"lat":4.8,"lon":-74.2,"tags":{"amenity":"parking","hgv":"yes","name":"Parqueadero La 50"}},
                  {"type":"way","id":2,"center":{"lat":4.9,"lon":-74.3},"tags":{"highway":"rest_area"}},
                  {"type":"node","id":3,"lat":4.85,"lon":-74.25,"tags":{"amenity":"restaurant"}},
                  {"type":"node","id":4,"lat":4.86,"lon":-74.26,
                   "tags":{"amenity":"fuel","brand":"Terpel","opening_hours":"24/7","phone":"3101234567"}}]}
                """);
        List<StopCandidate> rest = OverpassClient.parse(body, StopCategory.REST);
        assertThat(rest).extracting(StopCandidate::title)
                .containsExactly("Parqueadero La 50", "Área de descanso", "Terpel");
        assertThat(rest.get(0).truckFriendly()).isTrue();
        assertThat(rest.get(1).truckFriendly()).isTrue();
        assertThat(rest.get(2).openNow()).isTrue();
        assertThat(rest.get(2).phone()).isEqualTo("3101234567");
        assertThat(rest.get(2).category()).isEqualTo("Estación de servicio");
        assertThat(rest).allSatisfy(c -> assertThat(c.sources()).containsExactly("OSM"));
    }

    @Test
    void theOverpassQueryAsksEveryFilterInsideTheRouteBox() {
        String q = OverpassClient.query(StopCategory.REST, new double[]{4.6, -74.5, 5.1, -74.0});
        assertThat(q).startsWith("[out:json]")
                .contains("nwr[\"amenity\"=\"fuel\"](4.60000,-74.50000,5.10000,-74.00000);")
                .contains("[\"highway\"~\"^(services|rest_area)$\"]")
                .endsWith("out tags center 400;");
    }

    @Test
    void everyCategoryTheAppSendsExists() {
        for (String code : List.of("fuel", "restaurant", "coffee", "hotel", "truck_parking", "rest", "toilet",
                "workshop", "store", "atm", "pharmacy", "hospital")) {
            assertThat(StopCategory.of(code)).as(code).isPresent();
        }
        assertThat(StopCategory.of("casino")).isEmpty();
    }
}
