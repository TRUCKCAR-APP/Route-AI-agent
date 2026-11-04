package escuelaing.edu.co.truckcar.route_ai_agent.config;

import escuelaing.edu.co.truckcar.route_ai_agent.service.AlertInterpreter;
import escuelaing.edu.co.truckcar.route_ai_agent.service.AzureOpenAiAlertInterpreter;
import escuelaing.edu.co.truckcar.route_ai_agent.service.BedrockAlertInterpreter;
import escuelaing.edu.co.truckcar.route_ai_agent.service.RuleBasedAlertInterpreter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Locale;

/**
 * Elige el intérprete de alertas con TRUCKCAR_AI_PROVIDER:
 * <ul>
 *   <li>azure (por defecto): Azure OpenAI; sin endpoint ni clave, las reglas.</li>
 *   <li>bedrock: Amazon Bedrock (API Converse) con las credenciales por defecto de AWS.</li>
 *   <li>rules: solo reglas, sin llamar a ningún modelo ni contar fallbacks.</li>
 * </ul>
 * Un valor desconocido detiene el arranque: es mejor que clasificar en silencio con el proveedor equivocado.
 */
@Slf4j
@Configuration
public class AlertInterpreterConfig {

    @Bean
    @Primary
    public AlertInterpreter alertInterpreter(@Value("${truckcar.ai.provider:azure}") String provider,
                                             AzureOpenAiAlertInterpreter azure,
                                             BedrockAlertInterpreter bedrock,
                                             RuleBasedAlertInterpreter rules) {
        AlertInterpreter selected = select(provider, azure, bedrock, rules);
        log.info("Intérprete de alertas: {} ({})", provider, selected.getClass().getSimpleName());
        return selected;
    }

    static AlertInterpreter select(String provider, AlertInterpreter azure, AlertInterpreter bedrock,
                                   AlertInterpreter rules) {
        String value = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "azure" -> azure;
            case "bedrock" -> bedrock;
            case "rules" -> rules;
            default -> throw new IllegalStateException("TRUCKCAR_AI_PROVIDER='" + provider
                    + "' no es válido: use bedrock, azure o rules");
        };
    }
}
