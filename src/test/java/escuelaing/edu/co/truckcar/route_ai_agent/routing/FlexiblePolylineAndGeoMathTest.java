package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.PlanRouteRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class FlexiblePolylineAndGeoMathTest {

    @Test
    void decodesTheReferenceExampleOfTheFlexiblePolylineSpec() {
        // Ejemplo de https://github.com/heremaps/flexible-polyline (precisión 5, sin tercera dimensión).
        List<double[]> points = FlexiblePolyline.decode("BFoz5xJ67i1B1B7PzIhaxL7Y");

        assertThat(points).hasSize(4);
        assertThat(points.get(0)).containsExactly(new double[]{50.10228, 8.69821}, within(1e-9));
        assertThat(points.get(1)).containsExactly(new double[]{50.10201, 8.69567}, within(1e-9));
        assertThat(points.get(2)).containsExactly(new double[]{50.10063, 8.69150}, within(1e-9));
        assertThat(points.get(3)).containsExactly(new double[]{50.09878, 8.68752}, within(1e-9));
    }

    @Test
    void decodesAPolylineWithAThirdDimensionAndRejectsGarbage() {
        // Ejemplo con altitud de la especificación: la tercera dimensión se descarta.
        List<double[]> points = FlexiblePolyline.decode("BlBoz5xJ67i1BU1B7PUzIhaUxL7YU");
        assertThat(points).hasSize(4);
        assertThat(points.get(0)[0]).isCloseTo(50.10228, within(1e-9));

        assertThat(FlexiblePolyline.decode("")).isEmpty();
        assertThatThrownBy(() -> FlexiblePolyline.decode("B!!")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void simplifiesToTheLimitKeepingBothEnds() {
        List<double[]> line = new ArrayList<>();
        for (int i = 0; i <= 10_000; i++) {
            // Una curva suave de unos 110 km.
            line.add(new double[]{4.0 + i * 0.0001, -74.0 + Math.sin(i / 500.0) * 0.05});
        }

        List<double[]> simplified = GeoMath.simplify(line, 2_000);

        assertThat(simplified.size()).isLessThanOrEqualTo(2_000).isGreaterThan(10);
        assertThat(simplified.get(0)).isSameAs(line.get(0));
        assertThat(simplified.get(simplified.size() - 1)).isSameAs(line.get(line.size() - 1));
        assertThat(GeoMath.simplify(line.subList(0, 50), 2_000)).hasSize(50);
    }

    @Test
    void measuresTheDistanceFromAPointToTheRoute() {
        List<double[]> route = List.of(new double[]{4.60, -74.10}, new double[]{4.60, -74.00});

        // ~1,1 km al norte del tramo.
        assertThat(GeoMath.distanceToPolyline(4.61, -74.05, route)).isCloseTo(1_113, within(5.0));
        // Más allá del extremo: distancia al vértice.
        assertThat(GeoMath.distanceToPolyline(4.60, -73.99, route)).isCloseTo(1_110, within(5.0));
        assertThat(GeoMath.distanceToPolyline(4.6, -74.0, List.of())).isInfinite();
    }

    @Test
    void theTruckProfileUsesHereUnitsAndAxlesFromTheTruckType() {
        PlanRouteRequest mula = PlanRouteRequest.builder().truckType("tractomula_3s3").heightMeters(4.4)
                .grossWeightTons(52.0).build();
        TruckProfile profile = TruckProfile.from(mula);
        assertThat(profile.heightCm()).isEqualTo(440);
        assertThat(profile.grossWeightKg()).isEqualTo(52_000);
        assertThat(profile.axleCount()).isEqualTo(6);
        assertThat(profile.weightPerAxleKg()).isEqualTo(8_667);
        assertThat(profile.tractor()).isTrue();

        PlanRouteRequest override = PlanRouteRequest.builder().truckType("DOBLETROQUE").heightMeters(3.8)
                .grossWeightTons(28.0).axleCount(4).build();
        assertThat(TruckProfile.from(override).axleCount()).isEqualTo(4);
        assertThat(TruckProfile.from(override).tractor()).isFalse();

        PlanRouteRequest unknown = PlanRouteRequest.builder().truckType("REFRIGERADO").heightMeters(4.0)
                .grossWeightTons(20.0).build();
        assertThat(TruckProfile.from(unknown).axleCount()).isNull();
        assertThat(TruckProfile.from(unknown).weightPerAxleKg()).isNull();
    }
}
