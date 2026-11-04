package escuelaing.edu.co.truckcar.route_ai_agent.service;

import com.azure.core.exception.HttpResponseException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.util.function.Predicate;

/**
 * Qué fallos de un modelo vale la pena reintentar (retry-exception-predicate de las instancias azureOpenAi y bedrock).
 * Un 429 o throttling (cuota agotada, límite de la cuenta de estudiante), un 401/403 (clave o permisos), un 400
 * (contenido rechazado, modelo inválido) o un 404 (modelo no habilitado) no se arreglan en un segundo: reintentarlos
 * solo atrasa el fallback por reglas. Esos fallos van directo al fallback y siguen contando para el circuito.
 * Tiempos agotados, errores de red y 5xx sí se reintentan.
 */
public class TransientModelFailure implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof AwsServiceException aws) {
                return !aws.isThrottlingException() && isTransient(aws.statusCode());
            }
            if (t instanceof HttpResponseException http && http.getResponse() != null) {
                return isTransient(http.getResponse().getStatusCode());
            }
        }
        return true;
    }

    private static boolean isTransient(int status) {
        return status >= 500 || status == 408;
    }
}
