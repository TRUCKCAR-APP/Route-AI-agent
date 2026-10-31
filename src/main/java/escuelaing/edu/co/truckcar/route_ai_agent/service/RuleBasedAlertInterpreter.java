package escuelaing.edu.co.truckcar.route_ai_agent.service;

import escuelaing.edu.co.truckcar.route_ai_agent.service.AzureOpenAiAlertInterpreterService.AlertInterpretation;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Interpretación determinista de una alerta comunitaria por palabras clave. Es el fallback de Azure OpenAI: sin
 * credenciales, con el circuito abierto, ante un 429 o un timeout la alerta se clasifica igual y no se pierde.
 * <p>
 * La confianza es deliberadamente menor que el umbral de replanteo (0.70): una sola alerta interpretada por reglas no
 * desvía rutas; lo hace cuando la comunidad la confirma (alert.validated).
 */
@Component
public class RuleBasedAlertInterpreter {

    public static final String SOURCE = "RULES";

    /** Bloqueo del corredor: la vía no es transitable. */
    private static final List<Pattern> BLOCKING = patterns(
            "derrumbe", "deslizamiento", "avalancha", "via cerrada", "cierre total", "carretera cerrada",
            "paso cerrado", "bloqueo", "bloqueada", "bloqueado", "puente caido", "colapso", "inundada", "intransitable");
    /** Riesgo alto sin bloqueo confirmado. */
    private static final List<Pattern> HIGH = patterns(
            "accidente", "volcamiento", "incendio", "inundacion", "manifestacion", "paro", "protesta", "hundimiento");
    /** Afectaciones menores del tránsito. */
    private static final List<Pattern> MEDIUM = patterns(
            "paso a un carril", "un carril", "obra", "trancon", "congestion", "neblina", "lluvia", "via lenta");

    private static final double BLOCKING_CONFIDENCE = 0.6;
    private static final double HIGH_CONFIDENCE = 0.5;
    private static final double MEDIUM_CONFIDENCE = 0.4;
    private static final double UNKNOWN_CONFIDENCE = 0.2;

    public AlertInterpretation interpret(String alertText, String reportedCorridor) {
        String text = normalize(alertText);
        String corridor = reportedCorridor == null ? "" : reportedCorridor.trim();
        if (matchesAny(text, BLOCKING)) {
            boolean total = text.contains("cierre total") || text.contains("intransitable") || text.contains("puente caido");
            return new AlertInterpretation(true, corridor, total ? "CRITICAL" : "HIGH", BLOCKING_CONFIDENCE, SOURCE);
        }
        if (matchesAny(text, HIGH)) {
            return new AlertInterpretation(false, corridor, "HIGH", HIGH_CONFIDENCE, SOURCE);
        }
        if (matchesAny(text, MEDIUM)) {
            return new AlertInterpretation(false, corridor, "MEDIUM", MEDIUM_CONFIDENCE, SOURCE);
        }
        return new AlertInterpretation(false, corridor, "LOW", UNKNOWN_CONFIDENCE, SOURCE);
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
