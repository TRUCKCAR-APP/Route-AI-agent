package escuelaing.edu.co.truckcar.route_ai_agent.service;

/**
 * Interpreta el texto libre de una alerta comunitaria. Implementaciones: {@link RuleBasedAlertInterpreter},
 * {@link AzureOpenAiAlertInterpreter} y {@link BedrockAlertInterpreter}; la activa se elige con TRUCKCAR_AI_PROVIDER
 * (config/AlertInterpreterConfig).
 * <p>
 * Contrato: nunca lanza por una falla del proveedor. Si el modelo no responde, la alerta se clasifica por reglas.
 */
public interface AlertInterpreter {

    /**
     * @param reportedCorridor corredor que indicó el conductor; completa la respuesta si el texto no lo nombra
     * @throws IllegalArgumentException si el texto está vacío
     */
    AlertInterpretation interpret(String alertText, String reportedCorridor);

    default AlertInterpretation interpret(String alertText) {
        return interpret(alertText, null);
    }
}
