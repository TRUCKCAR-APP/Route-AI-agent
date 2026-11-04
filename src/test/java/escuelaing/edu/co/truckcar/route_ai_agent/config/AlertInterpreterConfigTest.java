package escuelaing.edu.co.truckcar.route_ai_agent.config;

import escuelaing.edu.co.truckcar.route_ai_agent.service.AlertInterpreter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AlertInterpreterConfigTest {

    private final AlertInterpreter azure = mock(AlertInterpreter.class);
    private final AlertInterpreter bedrock = mock(AlertInterpreter.class);
    private final AlertInterpreter rules = mock(AlertInterpreter.class);

    @Test
    void theProviderPicksTheInterpreterIgnoringCaseAndSpaces() {
        assertThat(AlertInterpreterConfig.select("bedrock", azure, bedrock, rules)).isSameAs(bedrock);
        assertThat(AlertInterpreterConfig.select(" Azure ", azure, bedrock, rules)).isSameAs(azure);
        assertThat(AlertInterpreterConfig.select("RULES", azure, bedrock, rules)).isSameAs(rules);
    }

    @Test
    void anUnknownProviderStopsTheStartup() {
        assertThatThrownBy(() -> AlertInterpreterConfig.select("openai", azure, bedrock, rules))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bedrock, azure o rules");
        assertThatThrownBy(() -> AlertInterpreterConfig.select(null, azure, bedrock, rules))
                .isInstanceOf(IllegalStateException.class);
    }
}
