package escuelaing.edu.co.truckcar.route_ai_agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba parametrizada del corpus de alertas colombianas.
 * Carga {@code alert-test-corpus.json} del classpath y verifica que
 * {@link RuleBasedAlertInterpreter} clasifique correctamente cada entrada.
 */
class RuleBasedAlertInterpreterCorpusTest {

    private static final RuleBasedAlertInterpreter INTERPRETER = new RuleBasedAlertInterpreter();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<Map<String, Object>> corpusEntries() throws Exception {
        try (InputStream is = RuleBasedAlertInterpreterCorpusTest.class
                .getClassLoader()
                .getResourceAsStream("alert-test-corpus.json")) {
            assertThat(is).as("alert-test-corpus.json debe estar en el classpath").isNotNull();
            List<Map<String, Object>> entries = MAPPER.readValue(is, new TypeReference<>() {});
            return entries.stream();
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("corpusEntries")
    void eachCorpusEntryMatchesExpectations(Map<String, Object> entry) {
        String text           = (String)  entry.get("text");
        String expectedType   = (String)  entry.get("expectedType");
        String expectedSev    = (String)  entry.get("expectedSeverity");
        boolean expectedBlocked = (Boolean) entry.get("expectedBlocked");

        AlertInterpretation result = INTERPRETER.interpret(text);

        assertThat(result.severity())
                .as("severity para: %s", text)
                .isEqualTo(expectedSev);

        assertThat(result.isCorridorBlocked())
                .as("isCorridorBlocked para: %s", text)
                .isEqualTo(expectedBlocked);

        assertThat(result.alertType())
                .as("alertType para: %s", text)
                .isEqualTo(expectedType);

        assertThat(result.source())
                .isEqualTo(RuleBasedAlertInterpreter.SOURCE);

        assertThat(result.voiceText())
                .as("voiceText para: %s", text)
                .isNotBlank();
    }
}
