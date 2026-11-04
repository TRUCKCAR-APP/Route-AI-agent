package escuelaing.edu.co.truckcar.route_ai_agent.service;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsJsonResponseFormat;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.http.policy.FixedDelayOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.core.util.HttpClientOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Interpretación con Azure OpenAI (TRUCKCAR_AI_PROVIDER=azure, el valor por defecto). Sin endpoint ni clave, todas las
 * alertas las clasifica {@link RuleBasedAlertInterpreter}. Resilience4j: instancia "azureOpenAi".
 */
@Service
public class AzureOpenAiAlertInterpreter extends ModelAlertInterpreter {

    public static final String SOURCE = "AZURE_OPENAI";
    static final String RESILIENCE_INSTANCE = "azureOpenAi";

    private final String endpoint;
    private final String apiKey;
    private final String deploymentName;
    private volatile OpenAIClient client;

    public AzureOpenAiAlertInterpreter(
            ObjectMapper objectMapper,
            RuleBasedAlertInterpreter rules,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            TimeLimiterRegistry timeLimiterRegistry,
            @Value("${azure.openai.endpoint:}") String endpoint,
            @Value("${azure.openai.api-key:}") String apiKey,
            @Value("${azure.openai.deployment-name:gpt-4o-mini}") String deploymentName) {
        super(objectMapper, rules, circuitBreakerRegistry, retryRegistry, timeLimiterRegistry, RESILIENCE_INSTANCE, "azure");
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.deploymentName = deploymentName;
    }

    @Override
    protected String source() {
        return SOURCE;
    }

    @Override
    protected boolean isConfigured() {
        return endpoint != null && !endpoint.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    protected String callModel(String systemPrompt, String userMessage) {
        ChatCompletionsOptions options = new ChatCompletionsOptions(List.of(
                new ChatRequestSystemMessage(systemPrompt),
                new ChatRequestUserMessage(userMessage)));
        options.setTemperature(0.0);
        options.setMaxTokens(150);
        options.setResponseFormat(new ChatCompletionsJsonResponseFormat());
        ChatCompletions completions = getClient().getChatCompletions(deploymentName, options);
        if (completions.getChoices() == null || completions.getChoices().isEmpty()
                || completions.getChoices().get(0).getMessage() == null
                || completions.getChoices().get(0).getMessage().getContent() == null) {
            throw new InvalidModelResponseException("Azure OpenAI devolvió una respuesta sin contenido", null);
        }
        return completions.getChoices().get(0).getMessage().getContent();
    }

    private OpenAIClient getClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = new OpenAIClientBuilder()
                            .endpoint(endpoint)
                            .credential(new AzureKeyCredential(apiKey))
                            // Los reintentos los controla Resilience4j: el SDK no reintenta por su cuenta.
                            .retryOptions(new RetryOptions(new FixedDelayOptions(0, Duration.ZERO)))
                            .clientOptions(new HttpClientOptions()
                                    .setConnectTimeout(Duration.ofSeconds(2))
                                    .setResponseTimeout(Duration.ofSeconds(4))
                                    .setReadTimeout(Duration.ofSeconds(4)))
                            .buildClient();
                }
            }
        }
        return client;
    }
}
