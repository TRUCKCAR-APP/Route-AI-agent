package escuelaing.edu.co.truckcar.route_ai_agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El despliegue de Azure OpenAI por defecto debe ser un modelo que todavía se pueda desplegar y que acepte los parámetros
 * del intérprete (temperature, max_tokens, respuesta JSON): gpt-4o-mini 2024-07-18 ya no se puede desplegar.
 */
class AzureDeploymentDefaultTest {

    @Test
    void theDefaultDeploymentIsGpt41Mini() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        Object deployment = sources.get(0).getProperty("azure.openai.deployment-name");

        assertThat(deployment).hasToString("${AZURE_OPENAI_DEPLOYMENT:gpt-4.1-mini}");
    }
}
