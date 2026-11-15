package escuelaing.edu.co.truckcar.route_ai_agent.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Interpretación determinista de una alerta comunitaria por palabras clave. Es el fallback de los modelos (Azure OpenAI,
 * Amazon Bedrock): sin credenciales, con el circuito abierto, ante un 429 o un timeout la alerta se clasifica igual y no
 * se pierde. Con TRUCKCAR_AI_PROVIDER=rules es el intérprete único.
 * <p>
 * La confianza es deliberadamente menor que el umbral de replanteo (0.70): una sola alerta interpretada por reglas no
 * desvía rutas; lo hace cuando la comunidad la confirma (alert.validated).
 */
@Component
public class RuleBasedAlertInterpreter implements AlertInterpreter {

    public static final String SOURCE = "RULES";

    /** Bloqueo del corredor: la vía no es transitable. */
    private static final List<Pattern> BLOCKING = patterns(
            "derrumbe", "deslizamiento", "avalancha", "via cerrada", "cierre total", "carretera cerrada",
            "paso cerrado", "bloqueo", "bloqueada", "bloqueado", "puente caido", "colapso", "inundada", "intransitable",
            "cerrado", "cerrada", "cierre", "no hay paso");
    /** Riesgo alto sin bloqueo confirmado. */
    private static final List<Pattern> HIGH = patterns(
            "accidente", "choque", "volcamiento", "colision", "incendio", "inundacion", "manifestacion", "paro", "protesta", "hundimiento");
    /** Afectaciones menores del tránsito. */
    private static final List<Pattern> MEDIUM = patterns(
            "paso a un carril", "un carril", "obra", "trancon", "congestion", "neblina", "niebla", "lluvia", "via lenta");

    // ── Tipos de alerta ──────────────────────────────────────────────────────
    private static final List<Pattern> TYPE_DERRUMBE = patterns(
            "derrumbe", "deslizamiento", "avalancha", "tierra en la via");
    private static final List<Pattern> TYPE_CIERRE = patterns(
            "cierre", "cerrado", "cerrada", "bloqueado", "bloqueada", "bloqueo", "no hay paso", "via cerrada");
    private static final List<Pattern> TYPE_ACCIDENTE = patterns(
            "accidente", "choque", "volcamiento", "colision");
    private static final List<Pattern> TYPE_RETEN = patterns(
            "reten", "control", "policia");
    private static final List<Pattern> TYPE_BASCULA = patterns(
            "bascula", "bascula de peso", "control de peso");
    private static final List<Pattern> TYPE_TRAFICO = patterns(
            "trancon", "trafico", "congestion", "lento", "lenta", "via lenta");

    private static final double BLOCKING_CONFIDENCE = 0.6;
    private static final double HIGH_CONFIDENCE = 0.5;
    private static final double MEDIUM_CONFIDENCE = 0.4;
    private static final double UNKNOWN_CONFIDENCE = 0.2;

    @Override
    public AlertInterpretation interpret(String alertText, String reportedCorridor) {
        String text = normalize(alertText);
        String corridor = reportedCorridor == null ? "" : reportedCorridor.trim();
        String alertType = detectType(text);
        if (matchesAny(text, BLOCKING)) {
            boolean total = text.contains("cierre total") || text.contains("intransitable") || text.contains("puente caido");
            String severity = total ? "CRITICAL" : "HIGH";
            String voiceText = generateVoiceText(alertType, corridor, true, severity);
            return new AlertInterpretation(true, corridor, severity, BLOCKING_CONFIDENCE, SOURCE, alertType, voiceText);
        }
        if (matchesAny(text, HIGH)) {
            String voiceText = generateVoiceText(alertType, corridor, false, "HIGH");
            return new AlertInterpretation(false, corridor, "HIGH", HIGH_CONFIDENCE, SOURCE, alertType, voiceText);
        }
        if (matchesAny(text, MEDIUM)) {
            String voiceText = generateVoiceText(alertType, corridor, false, "MEDIUM");
            return new AlertInterpretation(false, corridor, "MEDIUM", MEDIUM_CONFIDENCE, SOURCE, alertType, voiceText);
        }
        String voiceText = generateVoiceText(alertType, corridor, false, "LOW");
        return new AlertInterpretation(false, corridor, "LOW", UNKNOWN_CONFIDENCE, SOURCE, alertType, voiceText);
    }

    /**
     * Genera un mensaje claro y conciso para sintetizador de voz (TTS) para el conductor.
     */
    public static String generateVoiceText(String alertType, String corridor, boolean blocked, String severity) {
        String place = (corridor != null && !corridor.isBlank()) ? " en corredor " + corridor : "";
        if (blocked) {
            return switch (alertType) {
                case "DERRUMBE" -> "Alerta crítica: derrumbe reportado" + place + ". Vía cerrada, busque rutas alternas.";
                case "CIERRE" -> "Atención: vía cerrada" + place + ". Paso no habilitado.";
                default -> "Atención: vía bloqueada" + place + ". Tránsito suspendido.";
            };
        }
        return switch (alertType) {
            case "ACCIDENTE" -> "Precaución: accidente reportado" + place + ". Reduzca la velocidad.";
            case "TRAFICO" -> "Aviso de tráfico: congestión" + place + ". Se prevén demoras.";
            case "RETEN" -> "Aviso en ruta: control de autoridades o retén" + place + ".";
            case "BASCULA" -> "Aviso: control de báscula de pesaje" + place + ".";
            default -> "Precaución en ruta: condiciones adversas reportadas" + place + ".";
        };
    }

    /**
     * Clasifica el tipo fino de la alerta a partir del texto ya normalizado.
     * El orden importa: DERRUMBE prevalece sobre CIERRE cuando ambos aparecen.
     */
    private static String detectType(String normalizedText) {
        if (matchesAny(normalizedText, TYPE_DERRUMBE)) return "DERRUMBE";
        if (matchesAny(normalizedText, TYPE_ACCIDENTE)) return "ACCIDENTE";
        if (matchesAny(normalizedText, TYPE_CIERRE))    return "CIERRE";
        if (matchesAny(normalizedText, TYPE_BASCULA))   return "BASCULA";
        if (matchesAny(normalizedText, TYPE_RETEN))     return "RETEN";
        if (matchesAny(normalizedText, TYPE_TRAFICO))   return "TRAFICO";
        return "OTRO";
    }

    /** Minúsculas y sin tildes: "Vía cerrada" y "via cerrada" son la misma regla. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean matchesAny(String text, List<Pattern> patterns) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }

    private static List<Pattern> patterns(String... keywords) {
        return java.util.Arrays.stream(keywords)
                .map(keyword -> Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b"))
                .toList();
    }
}
