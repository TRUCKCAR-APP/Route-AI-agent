package escuelaing.edu.co.truckcar.route_ai_agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// El application.yml exige las credenciales de Azure por entorno; la prueba de contexto usa valores ficticios.
@SpringBootTest(properties = {
        "AZURE_OPENAI_ENDPOINT=https://example.invalid/",
        "AZURE_OPENAI_KEY=test-key"
})
class RouteAiAgentApplicationTests {

	@Test
	void contextLoads() {
	}

}
