package escuelaing.edu.co.truckcar.route_ai_agent.service;

/**
 * Lectura de una alerta comunitaria.
 *
 * @param source quién la clasificó: AZURE_OPENAI, BEDROCK o RULES
 */
public record AlertInterpretation(
        boolean isCorridorBlocked,
        String affectedCorridor,
        String severity,
        double confidenceScore,
        String source) {

    AlertInterpretation withCorridor(String corridor) {
        return new AlertInterpretation(isCorridorBlocked, corridor, severity, confidenceScore, source);
    }
}
