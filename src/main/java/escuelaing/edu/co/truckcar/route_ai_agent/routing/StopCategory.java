package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tipos de parada que el conductor pide por voz («quiero comer», «necesito descansar», «dónde tanqueo»), con lo que se
 * busca en HERE Discover y en OpenStreetMap (Overpass).
 * <p>
 * HERE no distingue los parqueaderos aptos para camión (los devuelve como «Aparcamiento de coches»): en
 * {@link #TRUCK_PARKING} y {@link #REST} solo se aceptan los de HERE cuyo nombre lo dice (parador, camiones, tractomulas);
 * OpenStreetMap sí marca hgv=yes, las áreas de servicio y las de descanso.
 */
public enum StopCategory {

    FUEL("fuel", List.of("estación de servicio"), List.of("[\"amenity\"=\"fuel\"]"), null, false),
    RESTAURANT("restaurant", List.of("restaurante"),
            List.of("[\"amenity\"~\"^(restaurant|fast_food|food_court)$\"]"), null, false),
    COFFEE("coffee", List.of("cafetería"), List.of("[\"amenity\"=\"cafe\"]"), null, false),
    HOTEL("hotel", List.of("hotel"), List.of("[\"tourism\"~\"^(hotel|motel|guest_house|hostel)$\"]"), null, false),
    TRUCK_PARKING("truck_parking", List.of("parqueadero de camiones", "parador"),
            List.of("[\"amenity\"=\"parking\"][\"hgv\"~\"^(yes|designated)$\"]", "[\"highway\"~\"^(services|rest_area)$\"]"),
            "Área de descanso", true),
    /** Descansar o «parar un rato»: estaciones de servicio, paradores y áreas de descanso. */
    REST("rest", List.of("estación de servicio", "parador"),
            List.of("[\"amenity\"=\"fuel\"]", "[\"amenity\"=\"parking\"][\"hgv\"~\"^(yes|designated)$\"]",
                    "[\"highway\"~\"^(services|rest_area)$\"]"),
            "Área de descanso", false),
    /** Baño: estaciones de servicio y baños públicos. */
    TOILET("toilet", List.of("estación de servicio"), List.of("[\"amenity\"~\"^(fuel|toilets)$\"]"), "Baños públicos", false),
    WORKSHOP("workshop", List.of("montallantas", "taller mecánico"),
            List.of("[\"shop\"~\"^(tyres|car_repair|truck_repair)$\"]", "[\"craft\"=\"mechanic\"]"), null, false),
    STORE("store", List.of("tienda"), List.of("[\"shop\"~\"^(convenience|supermarket)$\"]"), null, false),
    ATM("atm", List.of("cajero automático"), List.of("[\"amenity\"~\"^(atm|bank)$\"]"), null, false),
    PHARMACY("pharmacy", List.of("droguería"), List.of("[\"amenity\"=\"pharmacy\"]"), null, false),
    HOSPITAL("hospital", List.of("hospital"), List.of("[\"amenity\"~\"^(hospital|clinic)$\"]"), null, false);

    /** Nombres que dicen que el sitio recibe camiones (parador, «parqueadero de tractomulas», truck stop). */
    public static final Pattern TRUCK_NAME = Pattern.compile(
            "(?iu)\\b(parador|paradero|cami[oó]n(es)?|camioner[oa]s?|tractomulas?|mulas|carga pesada|truck|transportadores?)\\b");

    private final String code;
    private final List<String> hereQueries;
    private final List<String> overpassFilters;
    private final String unnamedTitle;
    private final boolean hereOnlyTruckNamed;

    StopCategory(String code, List<String> hereQueries, List<String> overpassFilters, String unnamedTitle,
                 boolean hereOnlyTruckNamed) {
        this.code = code;
        this.hereQueries = hereQueries;
        this.overpassFilters = overpassFilters;
        this.unnamedTitle = unnamedTitle;
        this.hereOnlyTruckNamed = hereOnlyTruckNamed;
    }

    public static Optional<StopCategory> of(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String wanted = code.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.code.equals(wanted)).findFirst();
    }

    public String code() {
        return code;
    }

    public List<String> hereQueries() {
        return hereQueries;
    }

    public List<String> overpassFilters() {
        return overpassFilters;
    }

    /** Nombre para un lugar de OpenStreetMap sin nombre (un área de descanso); null = se descarta. */
    public String unnamedTitle() {
        return unnamedTitle;
    }

    /** En HERE solo cuentan los resultados cuyo nombre dice que reciben camiones. */
    public boolean hereOnlyTruckNamed() {
        return hereOnlyTruckNamed;
    }

    /** Descansar y parquear la mula: se prefieren los sitios aptos para camión. */
    public boolean prefersTruckFriendly() {
        return this == REST || this == TRUCK_PARKING;
    }
}
