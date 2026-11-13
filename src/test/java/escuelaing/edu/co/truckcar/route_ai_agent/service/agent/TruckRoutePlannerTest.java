package escuelaing.edu.co.truckcar.route_ai_agent.service.agent;

import escuelaing.edu.co.truckcar.route_ai_agent.dto.client.AlertSummaryDto;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.PlanRouteRequest;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.AlternativeRouteResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.RouteLegResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.RoutePlanResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.model.RouteRiskLevel;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoute;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.TruckProfile;
import escuelaing.edu.co.truckcar.route_ai_agent.service.agent.TruckRoutePlanner.EvaluatedRoute;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TruckRoutePlannerTest {

    private final TruckRoutePlanner planner = new TruckRoutePlanner();
    private final TruckProfile mula = new TruckProfile(440, 52_000, 6, 8_667, true);
    private final PlanRouteRequest request = PlanRouteRequest.builder().vehiclePlate("SKD-123")
            .truckType("TRACTOMULA_3S3").heightMeters(4.4).grossWeightTons(52.0)
            .originCity("Bogotá").destinationCity("Buenaventura").build();

    /** Recta de 4.60,-74.10 a 4.60,-73.60 (~55 km), con un peaje en la mitad. */
    private static HereRoute route(List<String> labels, double km, double hours, List<String> violations) {
        List<double[]> geometry = new ArrayList<>();
        for (int i = 0; i <= 50; i++) {
            geometry.add(new double[]{4.60, -74.10 + i * 0.01});
        }
        List<HereRoute.Toll> tolls = List.of(new HereRoute.Toll("Peaje Chusacá", 4.60, -73.85, 70_700.0, "COP"));
        return new HereRoute(labels, km * 1000, hours * 3600, geometry, tolls, violations);
    }

    private static EvaluatedRoute ok(HereRoute route) {
        return new EvaluatedRoute(route, List.of(), List.of(), 4, true);
    }

    private static AlertSummaryDto alert(String type, String severity, double lat, double lng) {
        AlertSummaryDto alert = new AlertSummaryDto();
        alert.setAlertType(type);
        alert.setSeverity(severity);
        alert.setTitle(type + " reportado");
        alert.setMunicipality("Fusagasugá");
        alert.setLatitude(lat);
        alert.setLongitude(lng);
        return alert;
    }

    @Test
    void recommendsTheFastestPassableRouteWithItsTollsAndARealSummary() {
        RoutePlanResponse plan = planner.plan(request, mula,
                List.of(ok(route(List.of("40"), 499, 11.7, List.of())), ok(route(List.of("40", "25"), 511, 12.5, List.of()))),
                List.of());

        assertThat(plan.getRecommendedCorridor()).isEqualTo("Bogotá → Buenaventura por la vía 40");
        assertThat(plan.getRiskLevel()).isEqualTo(RouteRiskLevel.BAJO);
        assertThat(plan.getTotalDistanceKm()).isEqualTo(499.0);
        assertThat(plan.getEstimatedDurationHours()).isEqualTo(11.7);
        assertThat(plan.getProvider()).isEqualTo("HERE");
        assertThat(plan.getTollsCount()).isEqualTo(1);
        assertThat(plan.getTollCostCop()).isEqualTo(70_700.0);
        assertThat(plan.getGeometry()).hasSize(51).first().isEqualTo(List.of(4.6, -74.1));
        assertThat(plan.getRestrictionsVerified()).isTrue();
        assertThat(plan.getAiRecommendation())
                .contains("vía 40", "499 km", "1 peaje", "$70.700", "4,40 m", "52,0 t", "6 ejes",
                        "4 restricciones registradas", "Sin alertas")
                .doesNotContain("Agente", "IA");

        AlternativeRouteResponse alternative = plan.getAlternatives().get(0);
        assertThat(alternative.getCorridorName()).isEqualTo("Bogotá → Buenaventura por las vías 40 y 25");
        assertThat(alternative.getIsPassable()).isTrue();
        assertThat(alternative.getIssues()).contains("+12 km y +48 min frente a la recomendada");
        assertThat(alternative.getGeometry()).isNotEmpty();
    }

    @Test
    void theSegmentsGoFromTollToTollAlongTheRoute() {
        RoutePlanResponse plan = planner.plan(request, mula, List.of(ok(route(List.of("40"), 55, 1, List.of()))), List.of());

        List<RouteLegResponse> legs = plan.getSegments();
        assertThat(legs).hasSize(2);
        assertThat(legs.get(0).getSegmentName()).isEqualTo("Bogotá → Peaje Chusacá");
        assertThat(legs.get(0).getCheckpointName()).isEqualTo("Peaje Chusacá");
        assertThat(legs.get(0).getCondition()).isEqualTo("peaje $70.700");
        assertThat(legs.get(0).getDistanceKm()).isBetween(26.0, 29.0);
        assertThat(legs.get(1).getSegmentName()).isEqualTo("Peaje Chusacá → Buenaventura");
        assertThat(legs.get(0).getDistanceKm() + legs.get(1).getDistanceKm()).isCloseTo(55.0, org.assertj.core.data.Offset.offset(0.2));
    }

    @Test
    void whenTheFastestViolatesTheTruckLimitsTheNextOneIsChosenAndTheReasonIsShown() {
        HereRoute tooLow = route(List.of("45"), 480, 11, List.of("Route violates vehicle restriction: height limit of 420 cm"));
        HereRoute fine = route(List.of("40"), 499, 11.7, List.of());

        RoutePlanResponse plan = planner.plan(request, mula, List.of(ok(tooLow), ok(fine)), List.of());

        assertThat(plan.getRecommendedCorridor()).endsWith("por la vía 40");
        assertThat(plan.getRiskLevel()).isEqualTo(RouteRiskLevel.MEDIO);
        assertThat(plan.getWarnings().get(0)).startsWith("Se descartó la ruta más rápida (la vía 45)")
                .contains("height limit of 420 cm");
        AlternativeRouteResponse rejected = plan.getAlternatives().get(0);
        assertThat(rejected.getIsPassable()).isFalse();
        assertThat(rejected.getRiskLevel()).isEqualTo("INTRANSITABLE");
        assertThat(rejected.getIssues()).contains("Route violates vehicle restriction: height limit of 420 cm");
    }

    @Test
    void aRegisteredRestrictionAlsoBlocksARoute() {
        EvaluatedRoute blocked = new EvaluatedRoute(route(List.of("40"), 499, 11.7, List.of()),
                List.of("Gálibo de 4,20 m en el Puente La Vega: el camión mide 4,40 m"), List.of(), 3, true);
        EvaluatedRoute other = ok(route(List.of("50"), 530, 13, List.of()));

        RoutePlanResponse plan = planner.plan(request, mula, List.of(blocked, other), List.of());

        assertThat(plan.getRecommendedCorridor()).endsWith("por la vía 50");
        assertThat(plan.getAlternatives().get(0).getIssues()).contains("Gálibo de 4,20 m en el Puente La Vega: el camión mide 4,40 m");
    }

    @Test
    void withoutAnyPassableRouteThePlanIsImpassableAndSaysWhy() {
        HereRoute a = route(List.of("40"), 499, 11.7, List.of("height limit of 420 cm"));
        HereRoute b = route(List.of("25"), 511, 12.5, List.of("weight limit of 48 t"));

        RoutePlanResponse plan = planner.plan(request, mula, List.of(ok(a), ok(b)), List.of());

        assertThat(plan.getRiskLevel()).isEqualTo(RouteRiskLevel.INTRANSITABLE);
        assertThat(plan.getWarnings().get(0)).startsWith("Ninguna ruta es apta").contains("height limit of 420 cm");
        assertThat(plan.getAiRecommendation()).contains("Ninguna de las 2 rutas").contains("No se recomienda despachar");
        assertThat(plan.getSegments().get(plan.getSegments().size() - 1).getCondition()).startsWith("No apto");
    }

    @Test
    void onlyAlertsNearTheRouteCountAndASafeStopIsNotAHazard() {
        List<AlertSummaryDto> alerts = List.of(
                alert("DERRUMBE", "ALTA", 4.605, -73.90),       // ~550 m de la ruta
                alert("RETEN", "MEDIA", 4.61, -74.00),          // ~1,1 km
                alert("ACCIDENTE", "CRITICA", 5.50, -75.50),     // lejos
                alert("PARADOR_SEGURO", "BAJA", 4.60, -73.95));  // sobre la ruta, pero no es un peligro

        RoutePlanResponse plan = planner.plan(request, mula, List.of(ok(route(List.of("40"), 55, 1, List.of()))), alerts);

        assertThat(plan.getActiveAlertsCount()).isEqualTo(2);
        assertThat(plan.getRiskLevel()).isEqualTo(RouteRiskLevel.ALTO);
        assertThat(plan.getWarnings()).contains("Alerta crítica en la ruta: DERRUMBE reportado (Fusagasugá)",
                "Alerta en la ruta: RETEN reportado (Fusagasugá)");
        assertThat(plan.getAiRecommendation()).contains("2 alertas de la comunidad", "al menos una crítica");
    }

    @Test
    void unverifiedRestrictionsAreAWarningAndRaiseTheRisk() {
        EvaluatedRoute unverified = new EvaluatedRoute(route(List.of("40"), 499, 11.7, List.of()), List.of(), List.of(), 0, false);

        RoutePlanResponse plan = planner.plan(request, mula, List.of(unverified), List.of());

        assertThat(plan.getRestrictionsVerified()).isFalse();
        assertThat(plan.getRiskLevel()).isEqualTo(RouteRiskLevel.MEDIO);
        assertThat(plan.getWarnings()).anyMatch(w -> w.startsWith("No se pudieron verificar las restricciones"));
    }
}
