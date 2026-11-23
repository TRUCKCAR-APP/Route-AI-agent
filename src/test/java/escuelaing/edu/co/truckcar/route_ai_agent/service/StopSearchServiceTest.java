package escuelaing.edu.co.truckcar.route_ai_agent.service;

import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.StopSearchRequest;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.StopSearchResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RoutingProviderUnavailableException;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HerePlacesClient;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient.GeoPoint;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient.Trip;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.OverpassClient;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.StopCandidate;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.StopCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ruta recta hacia el norte desde (4,70; -74,05) hasta (5,00; -74,05), unos 33 km. Lugares: uno atrás, uno pegado a la
 * vía visto por HERE y por OpenStreetMap, uno a 3 km de la vía, uno más adelante y uno cuyo desvío real es de 7 km.
 */
class StopSearchServiceTest {

    private final HerePlacesClient herePlaces = mock(HerePlacesClient.class);
    private final OverpassClient overpass = mock(OverpassClient.class);
    private final HereRoutingClient routing = mock(HereRoutingClient.class);
    private final StopSearchService service = new StopSearchService(herePlaces, overpass, routing);

    static StopCandidate place(String source, String title, double lat, double lng, String phone, Boolean open) {
        return new StopCandidate(source + ":" + title, title, null, "Restaurante", lat, lng, phone, open, false, Set.of(source));
    }

    static StopSearchRequest request(String category) {
        List<List<Double>> ahead = new ArrayList<>();
        IntStream.rangeClosed(1, 30).forEach(i -> ahead.add(List.of(4.70 + i * 0.01, -74.05)));
        return StopSearchRequest.builder().latitude(4.70).longitude(-74.05).destinationLatitude(5.00).destinationLongitude(-74.05)
                .ahead(ahead).category(category).heightMeters(4.2).grossWeightTons(32.0).truckType("TRACTOMULA_3S3").build();
    }

    /** Viaje directo 33 km / 40 min; por cada lugar, lo que dice HERE de «aquí → lugar → destino». */
    private void trips(Map<Double, Trip> byStopLatitude) {
        doAnswer(inv -> {
            List<GeoPoint> via = inv.getArgument(1);
            if (via.isEmpty()) return Optional.of(new Trip(33000, 2400, List.of(new double[]{33000, 2400}), false));
            return Optional.ofNullable(byStopLatitude.get(via.get(0).latitude()));
        }).when(routing).trip(any(), any(), any(), any());
    }

    @BeforeEach
    void sources() {
        when(overpass.enabled()).thenReturn(true);
        when(herePlaces.searchAlongRoute(eq(StopCategory.RESTAURANT), anyString(), anyDouble(), anyDouble(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(
                        place("HERE", "Restaurante Atrás", 4.69, -74.05, null, null),
                        place("HERE", "Asadero La Vía", 4.75, -74.049, null, true),
                        place("HERE", "Restaurante Lejos de la Vía", 4.80, -74.02, null, null),
                        place("HERE", "Comidas El Peaje", 4.90, -74.05, null, null),
                        place("HERE", "Piqueteadero Desvío", 4.78, -74.04, null, null)));
        when(overpass.search(eq(StopCategory.RESTAURANT), any()))
                .thenReturn(List.of(place("OSM", "Asadero la Via", 4.7502, -74.0491, "3101234567", null)));
        trips(Map.of(
                4.75, new Trip(33100, 2420, List.of(new double[]{5600, 420}, new double[]{27500, 2000}), false),
                4.90, new Trip(33600, 2460, List.of(new double[]{22300, 1500}, new double[]{11300, 960}), false),
                4.78, new Trip(40000, 2900, List.of(new double[]{9000, 700}, new double[]{31000, 2200}), false)));
    }

    @Test
    void proposesOnlyPlacesAheadThatAddAtMostFiveKilometresClosestFirst() {
        StopSearchResponse result = service.search(request("restaurant"));

        assertThat(result.maxDetourMeters()).isEqualTo(5000);
        assertThat(result.aheadMeters()).isEqualTo(60000);
        assertThat(result.sources()).containsExactly("HERE", "OSM");
        assertThat(result.stops()).extracting(StopSearchResponse.Stop::title)
                .containsExactly("Asadero La Vía", "Comidas El Peaje");
        StopSearchResponse.Stop first = result.stops().get(0);
        assertThat(first.distanceMeters()).isEqualTo(5600);
        assertThat(first.secondsToStop()).isEqualTo(420);
        assertThat(first.detourMeters()).isEqualTo(100);
        assertThat(first.detourSeconds()).isEqualTo(20);
        // El mismo lugar en las dos fuentes se une: HERE da el nombre y si está abierto, OpenStreetMap el teléfono.
        assertThat(first.sources()).containsExactly("HERE", "OSM");
        assertThat(first.phone()).isEqualTo("3101234567");
        assertThat(first.openNow()).isTrue();
        // Lo que quedó atrás o a más de 2,5 km de la vía ni se enruta.
        verify(routing, never()).trip(any(), eq(List.of(new GeoPoint(4.69, -74.05))), any(), any());
        verify(routing, never()).trip(any(), eq(List.of(new GeoPoint(4.80, -74.02))), any(), any());
    }

    @Test
    void aShorterLimitOnTheDetourLeavesOutTheFartherOnes() {
        StopSearchRequest req = request("restaurant");
        req.setMaxDetourMeters(500);
        assertThat(service.search(req).stops()).extracting(StopSearchResponse.Stop::title).containsExactly("Asadero La Vía");
    }

    @Test
    void aPlaceTheTruckCannotReachIsNotProposed() {
        trips(Map.of(
                4.75, new Trip(33100, 2420, List.of(new double[]{5600, 420}), true),
                4.90, new Trip(33400, 2460, List.of(new double[]{22300, 1500}), false)));
        assertThat(service.search(request("restaurant")).stops()).extracting(StopSearchResponse.Stop::title)
                .containsExactly("Comidas El Peaje");
    }

    @Test
    void ifOpenStreetMapFailsHereAloneAnswers() {
        when(overpass.search(any(), any())).thenThrow(new IllegalStateException("timeout"));
        StopSearchResponse result = service.search(request("restaurant"));
        assertThat(result.sources()).containsExactly("HERE");
        assertThat(result.stops()).isNotEmpty();
    }

    @Test
    void ifBothSourcesFailTheServiceIsUnavailable() {
        when(herePlaces.searchAlongRoute(any(), anyString(), anyDouble(), anyDouble(), anyString(), anyInt(), anyInt()))
                .thenThrow(new RoutingProviderUnavailableException("HERE caído"));
        when(overpass.search(any(), any())).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(() -> service.search(request("restaurant"))).isInstanceOf(RoutingProviderUnavailableException.class);
    }

    @Test
    void toRestATruckFriendlyPlaceWinsOverASlightlyCloserOne() {
        when(herePlaces.searchAlongRoute(eq(StopCategory.REST), anyString(), anyDouble(), anyDouble(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(place("HERE", "Estación Cualquiera", 4.75, -74.049, null, null)));
        when(overpass.search(eq(StopCategory.REST), any())).thenReturn(List.of(new StopCandidate("osm:1", "Parador El Camionero",
                null, "Área de servicio", 4.78, -74.0495, null, null, true, Set.of("OSM"))));
        trips(Map.of(
                4.75, new Trip(33100, 2420, List.of(new double[]{5600, 420}), false),
                4.78, new Trip(33150, 2440, List.of(new double[]{8900, 640}), false)));
        assertThat(service.search(request("rest")).stops()).extracting(StopSearchResponse.Stop::title)
                .containsExactly("Parador El Camionero", "Estación Cualquiera");
    }
}
