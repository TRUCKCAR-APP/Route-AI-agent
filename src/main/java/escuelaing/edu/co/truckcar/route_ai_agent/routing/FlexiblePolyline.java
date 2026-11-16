package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodificador del formato Flexible Polyline de HERE (https://github.com/heremaps/flexible-polyline): la geometría
 * que devuelve Routing v8 en sections[].polyline. Devuelve puntos [latitud, longitud]; la tercera dimensión, si
 * viene, se descarta.
 */
public final class FlexiblePolyline {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final int[] DECODING = new int[128];

    static {
        java.util.Arrays.fill(DECODING, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            DECODING[ALPHABET.charAt(i)] = i;
        }
    }

    private FlexiblePolyline() {
    }

    public static List<double[]> decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return List.of();
        }
        Cursor cursor = new Cursor(encoded);
        long version = cursor.nextUnsigned();
        if (version != 1) {
            throw new IllegalArgumentException("Versión de Flexible Polyline no admitida: " + version);
        }
        long header = cursor.nextUnsigned();
        int precision = (int) (header & 15);
        int thirdDimension = (int) ((header >> 4) & 7);
        double factor = Math.pow(10, precision);

        List<double[]> points = new ArrayList<>();
        long lat = 0;
        long lng = 0;
        while (cursor.hasNext()) {
            lat += cursor.nextSigned();
            lng += cursor.nextSigned();
            if (thirdDimension != 0) {
                cursor.nextSigned();
            }
            points.add(new double[]{lat / factor, lng / factor});
        }
        return points;
    }

    private static final class Cursor {
        private final String encoded;
        private int index;

        Cursor(String encoded) {
            this.encoded = encoded;
        }

        boolean hasNext() {
            return index < encoded.length();
        }

        long nextUnsigned() {
            long result = 0;
            int shift = 0;
            while (true) {
                if (index >= encoded.length()) {
                    throw new IllegalArgumentException("Flexible Polyline incompleta");
                }
                char c = encoded.charAt(index++);
                int value = c < 128 ? DECODING[c] : -1;
                if (value < 0) {
                    throw new IllegalArgumentException("Carácter inválido en Flexible Polyline: " + c);
                }
                result |= (long) (value & 0x1F) << shift;
                if ((value & 0x20) == 0) {
                    return result;
                }
                shift += 5;
            }
        }

        long nextSigned() {
            long value = nextUnsigned();
            return (value & 1) != 0 ? ~(value >> 1) : value >> 1;
        }
    }
}
