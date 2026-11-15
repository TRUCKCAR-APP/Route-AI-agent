package escuelaing.edu.co.truckcar.route_ai_agent.service;

/**
 * Lectura de una alerta comunitaria.
 *
 * @param isCorridorBlocked indica si la vía está bloqueada
 * @param affectedCorridor  ubicación / corredor vial afectado
 * @param severity          nivel de severidad (LOW, MEDIUM, HIGH, CRITICAL)
 * @param confidenceScore   puntuación de confianza (0.0 a 1.0)
 * @param source            quién la clasificó: AZURE_OPENAI, BEDROCK o RULES
 * @param alertType         categoría fina de la alerta (DERRUMBE, CIERRE, ACCIDENTE, RETEN, BASCULA, TRAFICO, OTRO)
 * @param voiceText         mensaje sintetizado para lectura por voz al conductor
 */
public record AlertInterpretation(
        boolean isCorridorBlocked,
        String affectedCorridor,
        String severity,
        double confidenceScore,
        String source,
        String alertType,
        String voiceText) {

    /** Constructor de compatibilidad sin voiceText. */
    public AlertInterpretation(boolean isCorridorBlocked, String affectedCorridor,
                                String severity, double confidenceScore, String source, String alertType) {
        this(isCorridorBlocked, affectedCorridor, severity, confidenceScore, source, alertType, null);
    }

    /** Constructor de compatibilidad básico (para tests e intérpretes de modelo previos). */
    public AlertInterpretation(boolean isCorridorBlocked, String affectedCorridor,
                                String severity, double confidenceScore, String source) {
        this(isCorridorBlocked, affectedCorridor, severity, confidenceScore, source, null, null);
    }

    AlertInterpretation withCorridor(String corridor) {
        return new AlertInterpretation(isCorridorBlocked, corridor, severity, confidenceScore, source, alertType, voiceText);
    }
}
