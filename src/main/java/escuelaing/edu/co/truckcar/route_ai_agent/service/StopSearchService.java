package escuelaing.edu.co.truckcar.route_ai_agent.service;

import escuelaing.edu.co.truckcar.route_ai_agent.dto.request.StopSearchRequest;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.StopSearchResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RoutePlanningException;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RoutingProviderUnavailableException;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.FlexiblePolyline;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.GeoMath;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HerePlacesClient;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient.GeoPoint;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HereRoutingClient.Trip;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.OverpassClient;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.StopCandidate;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.StopCategory;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.TruckProfile;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Paradas sobre la ruta para lo que pide el conductor por voz («quiero comer», «necesito descansar»):
 * <ol>
 *   <li>Busca el tipo de lugar en el corredor de lo que falta de la ruta, en HERE y en OpenStreetMap.</li>
 *   <li>Une los repetidos y descarta lo que quedó atrás o lejos de la vía.</li>
 *   <li>Para los mejores candidatos calcula con HERE (perfil del camión) el viaje «aquí → lugar → destino»: descarta
 *       los que el camión no puede alcanzar o que desvían más de lo permitido (5 km por defecto).</li>
 *   <li>Ordena por lo que tarda en llegar más el doble del tiempo que suma al viaje: se proponen primero los cercanos que
 *       no lo hacen devolverse.</li>
 * </ol>
 * Las fuentes buscan; quien aprueba la parada es el motor de rutas para camión.
 */
@Slf4j
@Service
public class StopSearchService {

    static final int DEFAULT_MAX_DETOUR_M = 5000;
    static final int DEFAULT_AHEAD_M = 60000;
    static final int DEFAULT_LIMIT = 3;
    /** Candidatos a los que se les calcula el desvío con HERE (una ruta cada uno). */
    static final int ROUTED_CANDIDATES = 8;
    static final int CORRIDOR_POINTS = 60;
    static final int HERE_LIMIT = 20;
    /**
     * HERE ordena por cercanía al camión: con un solo corredor solo se verían los primeros kilómetros. La ruta se parte
     * en tramos de SEGMENT_M (hasta MAX_SEGMENTS) y se busca en cada uno desde su inicio.
     */
    static final double SEGMENT_M = 20000;
    static final int MAX_SEGMENTS = 4;
    /** Entre fuentes distintas, dos resultados a menos de esto son el mismo lugar; con nombre parecido, hasta SAME_NAME_M. */
    static final double SAME_PLACE_M = 25;
    static final double SAME_NAME_M = 250;
    /** OpenStreetMap es un complemento: no se espera por él más de esto desde que empieza la búsqueda. */
    static final long OSM_BUDGET_MS = 6000;

    private final HerePlacesClient herePlaces;
    private final OverpassClient overpass;
    private final HereRoutingClient routing;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public StopSearchService(HerePlacesClient herePlaces, OverpassClient overpass, HereRoutingClient routing) {
        this.herePlaces = herePlaces;
        this.overpass = overpass;
        this.routing = routing;
    }

    public StopSearchResponse search(StopSearchRequest request) {
        StopCategory category = StopCategory.of(request.getCategory())
                .orElseThrow(() -> new RoutePlanningException("Tipo de parada no admitido: " + request.getCategory()));
        int maxDetour = request.getMaxDetourMeters() == null ? DEFAULT_MAX_DETOUR_M : request.getMaxDetourMeters();
        int aheadM = request.getAheadMeters() == null ? DEFAULT_AHEAD_M : request.getAheadMeters();
        int limit = request.getLimit() == null ? DEFAULT_LIMIT : request.getLimit();
        TruckProfile truck = TruckProfile.of(request.getTruckType(), request.getAxleCount(),
                request.getHeightMeters(), request.getGrossWeightTons());
        GeoPoint here = new GeoPoint(request.getLatitude(), request.getLongitude());
        GeoPoint destination = new GeoPoint(request.getDestinationLatitude(), request.getDestinationLongitude());

        List<double[]> ahead = new ArrayList<>();
        ahead.add(new double[]{here.latitude(), here.longitude()});
        request.getAhead().forEach(p -> ahead.add(new double[]{p.get(0), p.get(1)}));
        List<double[]> path = GeoMath.head(ahead, aheadM);
        double[] cumulative = GeoMath.cumulativeDistances(path);
        // Desvío de ida y vuelta ≥ 2 × la distancia a la vía: más allá de la mitad del máximo no hay nada que medir.
        double maxOffset = maxDetour / 2.0;

        Found found = findCandidates(category, here, path, maxOffset);
        List<Placed> placed = new ArrayList<>();
        for (StopCandidate c : found.candidates()) {
            GeoMath.Projection at = GeoMath.project(c.latitude(), c.longitude(), path, cumulative);
            if (at.behindStart() || at.offsetMeters() > maxOffset || at.alongMeters() > aheadM) {
                continue;
            }
            placed.add(new Placed(c, at.alongMeters(), at.offsetMeters()));
        }
        placed.sort(Comparator.comparingDouble(p -> preScore(p, category)));
        List<StopSearchResponse.Stop> stops = new ArrayList<>();
        Trip base = null;
        for (int from = 0; from < Math.min(placed.size(), ROUTED_CANDIDATES * 2) && openCount(stops) < limit; from += ROUTED_CANDIDATES) {
            if (base == null) {
                base = routing.trip(here, List.of(), destination, truck)
                        .orElseThrow(() -> new RoutePlanningException("HERE no encontró ruta para el camión hasta el destino"));
            }
            stops.addAll(measure(placed.subList(from, Math.min(placed.size(), from + ROUTED_CANDIDATES)), here, destination,
                    truck, maxDetour, base));
        }
        stops.sort(Comparator.comparingDouble(s -> finalScore(s, category)));
        if (openCount(stops) >= limit) {
            stops.removeIf(s -> Boolean.FALSE.equals(s.openNow()));
        }
        log.info("Paradas {}: {} encontradas, {} adelante y cerca de la vía, {} propuestas (fuentes {})", category.code(),
                found.candidates().size(), placed.size(), stops.size(), found.sources());
        Metrics.counter("truckcar.stops.searches", "category", category.code(),
                "outcome", stops.isEmpty() ? "none" : "found").increment();
        return new StopSearchResponse(category.code(), aheadM, maxDetour, found.sources(),
                stops.subList(0, Math.min(limit, stops.size())));
    }

    private record Placed(StopCandidate candidate, double alongMeters, double offsetMeters) {
    }

    private record Found(List<StopCandidate> candidates, List<String> sources) {
    }

    /** HERE y OpenStreetMap en paralelo. Si las dos fallan, el servicio no está disponible; si falla una, sigue la otra. */
    private Found findCandidates(StopCategory category, GeoPoint here, List<double[]> path, double maxOffset) {
        int width = (int) Math.max(500, Math.min(10000, maxOffset * 2));
        List<CompletableFuture<List<StopCandidate>>> hereCalls = new ArrayList<>();
        for (List<double[]> segment : segments(path)) {
            String corridor = FlexiblePolyline.encode(GeoMath.simplify(segment, CORRIDOR_POINTS));
            double[] start = segment.get(0);
            for (String q : category.hereQueries()) {
                hereCalls.add(CompletableFuture.supplyAsync(() -> herePlaces.searchAlongRoute(category, q,
                        start[0], start[1], corridor, width, HERE_LIMIT), executor));
            }
        }
        CompletableFuture<List<StopCandidate>> osmCall = overpass.enabled()
                ? CompletableFuture.supplyAsync(() -> overpass.search(category, bbox(path, maxOffset)), executor)
                : CompletableFuture.completedFuture(null);

        long started = System.currentTimeMillis();
        List<StopCandidate> fromHere = new ArrayList<>();
        boolean hereAnswered = false;
        RuntimeException hereProblem = null;
        for (CompletableFuture<List<StopCandidate>> call : hereCalls) {
            try {
                fromHere.addAll(call.join());
                hereAnswered = true;
            } catch (RuntimeException e) {
                hereProblem = e;
            }
        }
        List<StopCandidate> fromOsm = null;
        try {
            long wait = Math.max(0, OSM_BUDGET_MS - (System.currentTimeMillis() - started));
            fromOsm = osmCall.get(wait, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            osmCall.cancel(true);
            log.warn("OpenStreetMap (Overpass) tardó más de {} ms: la búsqueda sigue sin él", OSM_BUDGET_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | RuntimeException e) {
            log.warn("OpenStreetMap (Overpass) no respondió a la búsqueda de paradas: {}", rootName(e));
        }
        if (!hereAnswered && fromOsm == null) {
            throw new RoutingProviderUnavailableException("No se pudo buscar lugares en la ruta: HERE ("
                    + rootName(hereProblem) + ") y OpenStreetMap no respondieron");
        }
        List<String> sources = new ArrayList<>();
        if (hereAnswered) sources.add("HERE");
        if (fromOsm != null) sources.add("OSM");
        List<StopCandidate> all = new ArrayList<>(fromHere);
        if (fromOsm != null) all.addAll(fromOsm);
        return new Found(dedupe(all), sources);
    }

    /** Tramos consecutivos de la ruta de SEGMENT_M cada uno (comparten el punto de unión). */
    static List<List<double[]>> segments(List<double[]> path) {
        double[] cumulative = GeoMath.cumulativeDistances(path);
        List<List<double[]>> out = new ArrayList<>();
        List<double[]> current = new ArrayList<>();
        double limit = SEGMENT_M;
        for (int i = 0; i < path.size(); i++) {
            current.add(path.get(i));
            if (cumulative[i] >= limit && i < path.size() - 1 && out.size() < MAX_SEGMENTS - 1) {
                out.add(current);
                current = new ArrayList<>();
                current.add(path.get(i));
                limit += SEGMENT_M;
            }
        }
        if (current.size() >= 2 || out.isEmpty()) out.add(current);
        return out;
    }

    /**
     * Une los repetidos: el mismo resultado de HERE en dos tramos (mismo id) y el mismo lugar en HERE y en
     * OpenStreetMap (nombre parecido o el mismo punto). Dos negocios vecinos de una misma fuente no se juntan.
     */
    static List<StopCandidate> dedupe(List<StopCandidate> all) {
        List<StopCandidate> unique = new ArrayList<>();
        for (StopCandidate c : all) {
            int same = -1;
            for (int i = 0; i < unique.size() && same < 0; i++) {
                StopCandidate u = unique.get(i);
                if (u.id() != null && u.id().equals(c.id())) {
                    same = i;
                    continue;
                }
                if (u.sources().containsAll(c.sources())) {
                    continue;
                }
                double d = GeoMath.distance(u.latitude(), u.longitude(), c.latitude(), c.longitude());
                if (d <= SAME_PLACE_M || (d <= SAME_NAME_M && similarNames(u.title(), c.title()))) {
                    same = i;
                }
            }
            if (same >= 0) {
                unique.set(same, unique.get(same).mergedWith(c));
            } else {
                unique.add(c);
            }
        }
        return unique;
    }

    static boolean similarNames(String a, String b) {
        String x = plain(a);
        String y = plain(b);
        return !x.isEmpty() && !y.isEmpty() && (x.contains(y) || y.contains(x));
    }

    private static String plain(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    /** Orden previo (sin rutas): lo que se alcanza antes y está pegado a la vía; abierto y apto para camión suman. */
    private static double preScore(Placed p, StopCategory category) {
        double score = p.alongMeters() + 3 * p.offsetMeters();
        if (Boolean.FALSE.equals(p.candidate().openNow())) score += 20000;
        if (category.prefersTruckFriendly() && p.candidate().truckFriendly()) score -= 3000;
        return score;
    }

    /** Calcula «aquí → lugar → destino» para cada candidato y compara con la ruta directa. */
    private static long openCount(List<StopSearchResponse.Stop> stops) {
        return stops.stream().filter(s -> !Boolean.FALSE.equals(s.openNow())).count();
    }

    private List<StopSearchResponse.Stop> measure(List<Placed> candidates, GeoPoint here, GeoPoint destination,
                                                  TruckProfile truck, int maxDetour, Trip base) {
        List<CompletableFuture<Optional<StopSearchResponse.Stop>>> calls = candidates.stream()
                .map(p -> CompletableFuture.supplyAsync(() -> viaStop(p, here, destination, truck, base, maxDetour), executor))
                .toList();
        List<StopSearchResponse.Stop> stops = new ArrayList<>();
        for (CompletableFuture<Optional<StopSearchResponse.Stop>> call : calls) {
            try {
                call.join().ifPresent(stops::add);
            } catch (RuntimeException e) {
                log.warn("No se pudo medir el desvío de un candidato: {}", rootName(e));
            }
        }
        return stops;
    }

    private Optional<StopSearchResponse.Stop> viaStop(Placed p, GeoPoint here, GeoPoint destination, TruckProfile truck,
                                                      Trip base, int maxDetour) {
        StopCandidate c = p.candidate();
        Optional<Trip> trip = routing.trip(here, List.of(new GeoPoint(c.latitude(), c.longitude())), destination, truck);
        if (trip.isEmpty() || trip.get().violatesTruckRestrictions() || trip.get().legs().isEmpty()) {
            return Optional.empty();
        }
        Trip t = trip.get();
        double detourM = Math.max(0, t.lengthMeters() - base.lengthMeters());
        double detourS = Math.max(0, t.durationSeconds() - base.durationSeconds());
        if (detourM > maxDetour) {
            return Optional.empty();
        }
        double[] toStop = t.legs().get(0);
        return Optional.of(new StopSearchResponse.Stop(c.id(), c.title(), c.address(), c.category(), c.latitude(),
                c.longitude(), toStop[0], toStop[1], detourM, detourS, c.phone(), c.openNow(), c.truckFriendly(),
                List.copyOf(new LinkedHashSet<>(c.sources()))));
    }

    static double finalScore(StopSearchResponse.Stop s, StopCategory category) {
        double score = s.secondsToStop() + 2 * s.detourSeconds();
        if (Boolean.FALSE.equals(s.openNow())) score += 1800;
        if (category.prefersTruckFriendly() && s.truckFriendly()) score -= 600;
        return score;
    }

    /** {sur, oeste, norte, este} de la ruta, ampliado en margin metros. */
    static double[] bbox(List<double[]> path, double margin) {
        double south = 90, west = 180, north = -90, east = -180;
        for (double[] p : path) {
            south = Math.min(south, p[0]);
            north = Math.max(north, p[0]);
            west = Math.min(west, p[1]);
            east = Math.max(east, p[1]);
        }
        double dLat = margin / 111_320d;
        double dLng = margin / (111_320d * Math.max(0.01, Math.cos(Math.toRadians((south + north) / 2))));
        return new double[]{south - dLat, west - dLng, north + dLat, east + dLng};
    }

    private static String rootName(Throwable e) {
        if (e == null) return "sin respuesta";
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t instanceof RoutingProviderUnavailableException || t instanceof RoutePlanningException
                ? t.getMessage() : t.getClass().getSimpleName();
    }

}
