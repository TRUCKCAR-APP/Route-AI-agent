package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Geometría sobre puntos [latitud, longitud]. Distancias en metros con una proyección local equirectangular, que a las
 * escalas de una ruta (metros a pocos kilómetros entre vértices) tiene un error despreciable.
 */
public final class GeoMath {

    private static final double METERS_PER_DEGREE_LAT = 111_320.0;

    private GeoMath() {
    }

    /** Distancia en metros del punto a la polilínea (al tramo más cercano). Infinita si la polilínea está vacía. */
    public static double distanceToPolyline(double lat, double lng, List<double[]> polyline) {
        if (polyline == null || polyline.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        if (polyline.size() == 1) {
            return distance(lat, lng, polyline.get(0)[0], polyline.get(0)[1]);
        }
        double best = Double.POSITIVE_INFINITY;
        for (int i = 1; i < polyline.size(); i++) {
            best = Math.min(best, distanceToSegment(lat, lng, polyline.get(i - 1), polyline.get(i)));
        }
        return best;
    }

    /**
     * Proyección de un punto sobre la polilínea: metros recorridos hasta el punto más cercano (alongMeters), distancia
     * a la vía (offsetMeters) y si queda antes del inicio (behindStart: el punto más cercano es el primero y el lugar
     * está del lado contrario al sentido de la ruta).
     */
    public record Projection(double alongMeters, double offsetMeters, boolean behindStart) {
    }

    public static Projection project(double lat, double lng, List<double[]> polyline, double[] cumulative) {
        if (polyline == null || polyline.size() < 2) {
            double d = polyline == null || polyline.isEmpty() ? Double.POSITIVE_INFINITY
                    : distance(lat, lng, polyline.get(0)[0], polyline.get(0)[1]);
            return new Projection(0, d, false);
        }
        double bestOffset = Double.POSITIVE_INFINITY;
        double bestAlong = 0;
        boolean behind = false;
        double kx = METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(lat));
        for (int i = 1; i < polyline.size(); i++) {
            double[] a = polyline.get(i - 1);
            double[] b = polyline.get(i);
            double ax = (a[1] - lng) * kx;
            double ay = (a[0] - lat) * METERS_PER_DEGREE_LAT;
            double dx = (b[1] - a[1]) * kx;
            double dy = (b[0] - a[0]) * METERS_PER_DEGREE_LAT;
            double lengthSquared = dx * dx + dy * dy;
            double raw = lengthSquared == 0 ? 0 : -(ax * dx + ay * dy) / lengthSquared;
            double t = Math.max(0, Math.min(1, raw));
            double px = ax + t * dx;
            double py = ay + t * dy;
            double offset = Math.sqrt(px * px + py * py);
            if (offset < bestOffset) {
                bestOffset = offset;
                bestAlong = cumulative[i - 1] + t * Math.sqrt(lengthSquared);
                behind = i == 1 && raw < 0;
            }
        }
        return new Projection(bestAlong, bestOffset, behind);
    }

    /** Los primeros maxMeters de la polilínea (corta el último tramo en el punto exacto). */
    public static List<double[]> head(List<double[]> points, double maxMeters) {
        List<double[]> out = new ArrayList<>();
        if (points == null || points.isEmpty()) {
            return out;
        }
        out.add(points.get(0));
        double done = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double step = distance(a[0], a[1], b[0], b[1]);
            if (done + step >= maxMeters) {
                double t = step == 0 ? 0 : (maxMeters - done) / step;
                out.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t});
                return out;
            }
            done += step;
            out.add(b);
        }
        return out;
    }

    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double kx = METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double dx = (lng2 - lng1) * kx;
        double dy = (lat2 - lat1) * METERS_PER_DEGREE_LAT;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double distanceToSegment(double lat, double lng, double[] a, double[] b) {
        double kx = METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(lat));
        double ax = (a[1] - lng) * kx;
        double ay = (a[0] - lat) * METERS_PER_DEGREE_LAT;
        double bx = (b[1] - lng) * kx;
        double by = (b[0] - lat) * METERS_PER_DEGREE_LAT;
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / lengthSquared));
        double px = ax + t * dx;
        double py = ay + t * dy;
        return Math.sqrt(px * px + py * py);
    }

    /**
     * Simplifica la polilínea (Douglas-Peucker) hasta como máximo maxPoints, empezando con una tolerancia de 5 m y
     * duplicándola mientras sobren puntos. Conserva siempre el primero y el último.
     */
    public static List<double[]> simplify(List<double[]> points, int maxPoints) {
        if (points == null || points.size() <= Math.max(2, maxPoints)) {
            return points == null ? List.of() : points;
        }
        double tolerance = 5.0;
        List<double[]> result = douglasPeucker(points, tolerance);
        while (result.size() > maxPoints) {
            tolerance *= 2;
            result = douglasPeucker(points, tolerance);
        }
        return result;
    }

    private static List<double[]> douglasPeucker(List<double[]> points, double tolerance) {
        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{0, points.size() - 1});
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int start = range[0];
            int end = range[1];
            double maxDistance = -1;
            int index = -1;
            for (int i = start + 1; i < end; i++) {
                double[] p = points.get(i);
                double d = distanceToSegment(p[0], p[1], points.get(start), points.get(end));
                if (d > maxDistance) {
                    maxDistance = d;
                    index = i;
                }
            }
            if (index > 0 && maxDistance > tolerance) {
                keep[index] = true;
                stack.push(new int[]{start, index});
                stack.push(new int[]{index, end});
            }
        }
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (keep[i]) {
                result.add(points.get(i));
            }
        }
        return result;
    }

    /** [[lat, lng], ...] con 5 decimales (~1 m), el formato de las respuestas y de Routing-Restrictions. */
    public static List<List<Double>> toLatLngList(List<double[]> points) {
        List<List<Double>> list = new ArrayList<>(points.size());
        for (double[] p : points) {
            list.add(List.of(round5(p[0]), round5(p[1])));
        }
        return list;
    }

    /** LineString GeoJSON (coordenadas [longitud, latitud], el orden de GeoJSON) con 5 decimales. */
    public static java.util.Map<String, Object> toGeoJsonLineString(List<double[]> points) {
        List<List<Double>> coordinates = new ArrayList<>(points.size());
        for (double[] p : points) {
            coordinates.add(List.of(round5(p[1]), round5(p[0])));
        }
        java.util.Map<String, Object> geoJson = new java.util.LinkedHashMap<>();
        geoJson.put("type", "LineString");
        geoJson.put("coordinates", coordinates);
        return geoJson;
    }

    /** Distancia acumulada en metros desde el primer punto hasta cada punto de la polilínea. */
    public static double[] cumulativeDistances(List<double[]> points) {
        double[] cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            cumulative[i] = cumulative[i - 1] + distance(points.get(i - 1)[0], points.get(i - 1)[1],
                    points.get(i)[0], points.get(i)[1]);
        }
        return cumulative;
    }

    private static double round5(double value) {
        return Math.round(value * 100_000d) / 100_000d;
    }
}
