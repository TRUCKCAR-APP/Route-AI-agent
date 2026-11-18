package escuelaing.edu.co.truckcar.route_ai_agent.service;

import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.RerouteRequest;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.RerouteResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoute;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient.AvoidArea;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient.GeoPoint;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.TruckProfile;
import escuelaing.edu.co.truckcar.route_ai_agent.service.agent.TruckRoutePlanner;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Recálculo con HERE (perfil de camión y tráfico) desde la posición actual. Sin la clave de HERE, o si HERE no
 * responde, lanza RoutingProviderUnavailableException (503) y el front recalcula con su respaldo.
 */
@Service
@RequiredArgsConstructor
public class RerouteService {

    static final double DEFAULT_AVOID_RADIUS_M = 300;

    private final HereRoutingClient hereRouting;

    public RerouteResponse reroute(RerouteRequest request) {
        TruckProfile truck = TruckProfile.of(request.getTruckType(), request.getAxleCount(),
                request.getHeightMeters(), request.getGrossWeightTons());
        List<AvoidArea> avoid = request.getAvoid() == null ? List.of() : request.getAvoid().stream()
                .map(p -> new AvoidArea(p.getLatitude(), p.getLongitude(),
                        p.getRadiusMeters() == null ? DEFAULT_AVOID_RADIUS_M : p.getRadiusMeters()))
                .toList();
        List<HereRoute> routes = hereRouting.routes(
                new GeoPoint(request.getOriginLatitude(), request.getOriginLongitude()),
                new GeoPoint(request.getDestinationLatitude(), request.getDestinationLongitude()),
                truck, avoid);
        List<RerouteResponse.Option> options = routes.stream()
                // Orden estable: las aptas para el camión primero, conservando el orden de HERE dentro de cada grupo.
                .sorted(Comparator.comparing(HereRoute::violatesTruckRestrictions))
                .map(route -> new RerouteResponse.Option(route.lengthMeters(), route.durationSeconds(), route.geometry(),
                        TruckRoutePlanner.steps(route), route.violations(), route.tollCost()))
                .toList();
        Metrics.counter("truckcar.routing.reroutes", "avoid", avoid.isEmpty() ? "no" : "yes").increment();
        return new RerouteResponse("HERE", options);
    }
}
