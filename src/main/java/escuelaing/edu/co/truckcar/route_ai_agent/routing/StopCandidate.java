package escuelaing.edu.co.truckcar.route_ai_agent.routing;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Un lugar candidato a parada, tal como lo da una fuente (HERE u OpenStreetMap), antes de medir el desvío.
 *
 * @param openNow       true/false si la fuente sabe si está abierto ahora (HERE); null si no se sabe
 * @param truckFriendly la fuente o el nombre dicen que recibe camiones (hgv=yes, área de servicio, «parador»)
 */
public record StopCandidate(String id, String title, String address, String category, double latitude, double longitude,
                            String phone, Boolean openNow, boolean truckFriendly, Set<String> sources) {

    /** Une el mismo lugar visto en dos fuentes: los datos de la primera mandan y la segunda completa lo que falte. */
    public StopCandidate mergedWith(StopCandidate other) {
        Set<String> all = new LinkedHashSet<>(sources);
        all.addAll(other.sources);
        return new StopCandidate(id, title,
                address != null ? address : other.address,
                category != null ? category : other.category,
                latitude, longitude,
                phone != null ? phone : other.phone,
                openNow != null ? openNow : other.openNow,
                truckFriendly || other.truckFriendly,
                all);
    }
}
