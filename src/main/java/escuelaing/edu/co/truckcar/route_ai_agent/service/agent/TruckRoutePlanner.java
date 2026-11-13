package escuelaing.edu.co.truckcar.route_ai_agent.service.agent;

import escuelaing.edu.co.truckcar.route_ai_agent.dto.client.AlertSummaryDto;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.PlanRouteRequest;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.AlternativeRouteResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.RouteLegResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.RoutePlanResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.TollResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.model.RouteRiskLevel;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.GeoMath;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoute;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.TruckProfile;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Elige la ruta del camión entre las que calculó HERE (perfil de camión) y arma la respuesta. Una ruta es apta si HERE
 * no reporta que viole las restricciones del vehículo y si ninguna restricción registrada en Routing-Restrictions
 * (gálibos, límites de peso, cierres) la bloquea. Se recomienda la primera apta en el orden de HERE (la más rápida).
 * <p>
 * Nada se inventa: sin ruta apta, el plan queda INTRANSITABLE con los motivos; las alertas de la comunidad cuentan
 * solo si están a menos de {@link #ALERT_RADIUS_METERS} de la ruta.
 */
@Component
public class TruckRoutePlanner {

    public static final String PROVIDER = "HERE";
    public static final double ALERT_RADIUS_METERS = 2_000;
    static final int MAX_GEOMETRY_POINTS = 2_000;
    static final int MAX_ALTERNATIVE_POINTS = 500;

    /** Alertas que elevan el riesgo a ALTO. Un parador seguro no es un peligro y no cuenta como alerta en la ruta. */
    private static final Set<String> CRITICAL_TYPES = Set.of("DERRUMBE", "ACCIDENTE");
    private static final Set<String> NOT_A_HAZARD = Set.of("PARADOR_SEGURO");

    private static final Locale CO = Locale.forLanguageTag("es-CO");

    /**
     * Una ruta de HERE con el resultado de Routing-Restrictions.
     *
     * @param blockingReasons      restricciones registradas que la bloquean para este camión
     * @param restrictionWarnings  restricciones cercanas que no la bloquean
     * @param restrictionsVerified false si Routing-Restrictions no respondió
     */
    public record EvaluatedRoute(HereRoute route, List<String> blockingReasons, List<String> restrictionWarnings,
                                 int restrictionsEvaluated, boolean restrictionsVerified) {

        public boolean passable() {
            return !route.violatesTruckRestrictions() && blockingReasons.isEmpty();
        }

        public List<String> reasons() {
            List<String> reasons = new ArrayList<>(route.violations());
            reasons.addAll(blockingReasons);
            return reasons;
        }
    }

    public RoutePlanResponse plan(PlanRouteRequest request, TruckProfile truck, List<EvaluatedRoute> routes,
                                  List<AlertSummaryDto> alerts) {
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("Se necesita al menos una ruta para planear");
        }
        int chosenIndex = 0;
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).passable()) {
                chosenIndex = i;
                break;
            }
        }
        EvaluatedRoute chosen = routes.get(chosenIndex);
        boolean passable = chosen.passable();
        String origin = request.getOriginCity();
        String destination = request.getDestinationCity();
        List<double[]> geometry = GeoMath.simplify(chosen.route().geometry(), MAX_GEOMETRY_POINTS);

        List<String> warnings = new ArrayList<>();
        if (!passable) {
            warnings.add("Ninguna ruta es apta para este camión: " + String.join(" | ", chosen.reasons()));
        } else if (chosenIndex > 0) {
            EvaluatedRoute fastest = routes.get(0);
            warnings.add("Se descartó la ruta más rápida (" + label(fastest.route(), 0) + "): "
                    + String.join(" | ", fastest.reasons()));
        }
        warnings.addAll(chosen.restrictionWarnings());
        if (!chosen.restrictionsVerified()) {
            warnings.add("No se pudieron verificar las restricciones registradas en TruckCar (gálibos y cierres); "
                    + "la ruta solo cumple las restricciones del mapa de HERE");
        }

        List<AlertSummaryDto> onRoute = alertsNear(alerts, geometry);
        boolean critical = false;
        for (AlertSummaryDto alert : onRoute) {
            boolean isCritical = CRITICAL_TYPES.contains(upper(alert.getAlertType()))
                    || "CRITICA".equals(upper(alert.getSeverity()));
            critical |= isCritical;
            warnings.add((isCritical ? "Alerta crítica en la ruta: " : "Alerta en la ruta: ") + alert.getTitle()
                    + (alert.getMunicipality() != null ? " (" + alert.getMunicipality() + ")" : ""));
        }

        RouteRiskLevel risk;
        if (!passable) {
            risk = RouteRiskLevel.INTRANSITABLE;
        } else if (critical) {
            risk = RouteRiskLevel.ALTO;
        } else if (!onRoute.isEmpty() || chosenIndex > 0 || !chosen.restrictionsVerified()) {
            risk = RouteRiskLevel.MEDIO;
        } else {
            risk = RouteRiskLevel.BAJO;
        }

        List<AlternativeRouteResponse> alternatives = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) {
            if (i == chosenIndex) {
                continue;
            }
            EvaluatedRoute alt = routes.get(i);
            List<String> issues = new ArrayList<>(alt.reasons());
            double extraKm = (alt.route().lengthMeters() - chosen.route().lengthMeters()) / 1000;
            double extraMin = (alt.route().durationSeconds() - chosen.route().durationSeconds()) / 60;
            issues.add(String.format(CO, "%+.0f km y %+.0f min frente a la recomendada", extraKm, extraMin));
            alternatives.add(AlternativeRouteResponse.builder()
                    .corridorName(corridorName(origin, destination, alt.route(), i))
                    .distanceKm(km(alt.route()))
                    .estimatedHours(hours(alt.route()))
                    .isPassable(alt.passable())
                    .riskLevel(alt.passable() ? RouteRiskLevel.MEDIO.name() : RouteRiskLevel.INTRANSITABLE.name())
                    .issues(issues)
                    .tollCostCop(alt.route().tollCost())
                    .geometry(GeoMath.toLatLngList(GeoMath.simplify(alt.route().geometry(), MAX_ALTERNATIVE_POINTS)))
                    .build());
        }

        List<TollResponse> tolls = chosen.route().tolls().stream()
                .map(t -> TollResponse.builder().name(t.name()).latitude(t.latitude()).longitude(t.longitude())
                        .cost(t.cost()).currency(t.currency()).build())
                .toList();

        return RoutePlanResponse.builder()
                .vehiclePlate(request.getVehiclePlate())
                .originCity(origin)
                .destinationCity(destination)
                .recommendedCorridor(corridorName(origin, destination, chosen.route(), chosenIndex))
                .totalDistanceKm(km(chosen.route()))
                .estimatedDurationHours(hours(chosen.route()))
                .riskLevel(risk)
                .activeAlertsCount(onRoute.size())
                .segments(legs(origin, destination, chosen.route(), geometry, passable))
                .warnings(warnings)
                .alternatives(alternatives)
                .aiRecommendation(summary(truck, routes, chosenIndex, onRoute.size(), critical))
                .generatedAt(LocalDateTime.now())
                .provider(PROVIDER)
                .geometry(GeoMath.toLatLngList(geometry))
                .tolls(tolls)
                .tollsCount(tolls.size())
                .tollCostCop(chosen.route().tollCost())
                .restrictionsVerified(chosen.restrictionsVerified())
                .build();
    }

    private static List<AlertSummaryDto> alertsNear(List<AlertSummaryDto> alerts, List<double[]> geometry) {
        List<AlertSummaryDto> near = new ArrayList<>();
        for (AlertSummaryDto alert : alerts == null ? List.<AlertSummaryDto>of() : alerts) {
            if (alert.getLatitude() == null || alert.getLongitude() == null
                    || NOT_A_HAZARD.contains(upper(alert.getAlertType()))) {
                continue;
            }
            if (GeoMath.distanceToPolyline(alert.getLatitude(), alert.getLongitude(), geometry) <= ALERT_RADIUS_METERS) {
                near.add(alert);
            }
        }
        return near;
    }

    /** Un tramo por peaje (origen → peaje 1 → … → destino), con la distancia a lo largo de la ruta. */
    private static List<RouteLegResponse> legs(String origin, String destination, HereRoute route,
                                               List<double[]> geometry, boolean passable) {
        String condition = passable ? "Apto para el camión" : "No apto: " + String.join(" | ", route.violations());
        if (route.tolls().isEmpty() || geometry.size() < 2) {
            return List.of(new RouteLegResponse(origin + " → " + destination, km(route), condition, null));
        }
        double[] cumulative = new double[geometry.size()];
        for (int i = 1; i < geometry.size(); i++) {
            cumulative[i] = cumulative[i - 1] + GeoMath.distance(geometry.get(i - 1)[0], geometry.get(i - 1)[1],
                    geometry.get(i)[0], geometry.get(i)[1]);
        }
        // Escala la distancia de la geometría simplificada al largo real que informó HERE.
        double scale = cumulative[cumulative.length - 1] > 0 ? route.lengthMeters() / cumulative[cumulative.length - 1] : 1;
        List<RouteLegResponse> legs = new ArrayList<>();
        String from = origin;
        double fromMeters = 0;
        for (HereRoute.Toll toll : route.tolls()) {
            double at = toll.latitude() == null ? fromMeters : nearestAlong(toll.latitude(), toll.longitude(), geometry, cumulative) * scale;
            at = Math.max(at, fromMeters);
            String fare = toll.cost() == null ? "tarifa no disponible" : "peaje " + money(toll.cost());
            legs.add(new RouteLegResponse(from + " → " + toll.name(), round1((at - fromMeters) / 1000), fare, toll.name()));
            from = toll.name();
            fromMeters = at;
        }
        legs.add(new RouteLegResponse(from + " → " + destination, round1((route.lengthMeters() - fromMeters) / 1000),
                condition, null));
        return legs;
    }

    private static double nearestAlong(double lat, double lng, List<double[]> geometry, double[] cumulative) {
        int best = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < geometry.size(); i++) {
            double d = GeoMath.distance(lat, lng, geometry.get(i)[0], geometry.get(i)[1]);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return cumulative[best];
    }

    private static String summary(TruckProfile truck, List<EvaluatedRoute> routes, int chosenIndex, int alertsOnRoute,
                                  boolean critical) {
        EvaluatedRoute chosen = routes.get(chosenIndex);
        HereRoute route = chosen.route();
        String vehicle = String.format(CO, "%.2f m de altura, %.1f t de peso bruto%s", truck.heightMeters(),
                truck.grossWeightTons(), truck.axleCount() == null ? "" : " y " + truck.axleCount() + " ejes");
        if (!chosen.passable()) {
            return "Ninguna de las " + routes.size() + " rutas de HERE es apta para un camión de " + vehicle + ": "
                    + String.join(" | ", chosen.reasons()) + ". No se recomienda despachar por esta vía.";
        }
        StringBuilder text = new StringBuilder();
        text.append(String.format(CO, "Ruta por %s: %.0f km y %s, %d peaje%s (%s). ", label(route, chosenIndex),
                route.lengthMeters() / 1000, duration(route.durationSeconds()), route.tolls().size(),
                route.tolls().size() == 1 ? "" : "s", money(route.tollCost())));
        text.append("HERE la calculó con el perfil de un camión de ").append(vehicle).append(". ");
        if (chosenIndex > 0) {
            text.append("La ruta más rápida se descartó porque ").append(String.join(" | ", routes.get(0).reasons()))
                    .append(". ");
        }
        text.append(chosen.restrictionsVerified()
                ? (chosen.restrictionsEvaluated() == 0
                        ? "No hay restricciones registradas en TruckCar sobre el trayecto. "
                        : "Se revisaron " + chosen.restrictionsEvaluated() + " restricciones registradas en TruckCar y ninguna la bloquea. ")
                : "Las restricciones registradas en TruckCar no se pudieron verificar. ");
        if (alertsOnRoute > 0) {
            text.append(alertsOnRoute).append(alertsOnRoute == 1 ? " alerta" : " alertas")
                    .append(" de la comunidad a menos de 2 km de la ruta")
                    .append(critical ? ", al menos una crítica." : ".");
        } else {
            text.append("Sin alertas de la comunidad a menos de 2 km de la ruta.");
        }
        return text.toString();
    }

    static String corridorName(String origin, String destination, HereRoute route, int index) {
        return origin + " → " + destination + " por " + label(route, index);
    }

    static String label(HereRoute route, int index) {
        List<String> labels = route.labels();
        if (labels.isEmpty()) {
            return "la ruta " + (index + 1);
        }
        if (labels.size() == 1) {
            return "la vía " + labels.get(0);
        }
        return "las vías " + String.join(", ", labels.subList(0, labels.size() - 1)) + " y " + labels.get(labels.size() - 1);
    }

    private static String duration(double seconds) {
        long minutes = Math.round(seconds / 60);
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }

    private static String money(double value) {
        NumberFormat format = NumberFormat.getIntegerInstance(CO);
        return "$" + format.format(Math.round(value));
    }

    private static Double km(HereRoute route) {
        return round1(route.lengthMeters() / 1000);
    }

    private static Double hours(HereRoute route) {
        return Math.round(route.durationSeconds() / 360.0) / 10.0;
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
