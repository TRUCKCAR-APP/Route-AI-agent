package escuelaing.edu.co.truckcar.route_ai_agent.service;

import com.azure.core.exception.HttpResponseException;

import java.util.function.Predicate;

/**
 * Qué fallos de Azure OpenAI vale la pena reintentar (resilience4j.retry.instances.azureOpenAi.retry-exception-predicate).
 * Un 429 (cuota agotada o límite de la cuenta de estudiante), un 401/403 (clave inválida) o un 400 (contenido rechazado)
 * no se arreglan en un segundo: reintentarlos solo atrasa el fallback por reglas. Esos fallos van directo al fallback y
 * siguen contando para el circuito. Tiempos agotados, errores de red y 5xx sí se reintentan.
 */
public class TransientAzureFailure implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof HttpResponseException http && http.getResponse() != null) {
                int status = http.getResponse().getStatusCode();
                return status >= 500 || status == 408;
            }
        }
        return true;
    }
}
