package escuelaing.edu.co.truckcar.route_ai_agent.service;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.core.credential.AzureKeyCredential;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class AzureOpenAiAlertInterpreterService {

    private static final String SYSTEM_PROMPT = """
            You classify community road alerts. Return only one valid JSON object, with no markdown:
            {
              "isCorridorBlocked": boolean,
              "affectedCorridor": string,
              "severity": "LOW" | "MEDIUM" | "HIGH" | "CRITICAL",
              "confidenceScore": number
            }
            Infer only what the alert states or clearly implies. Use an empty string when the corridor
            is unknown. confidenceScore must be between 0 and 1. Do not add other fields.
            """;

    private final String endpoint;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final String deploymentName;
    private volatile OpenAIClient client;

    public AzureOpenAiAlertInterpreterService(
            ObjectMapper objectMapper,
            @Value("${azure.openai.endpoint}") String endpoint,
            @Value("${azure.openai.api-key}") String apiKey,
            @Value("${azure.openai.deployment-name}") String deploymentName) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.deploymentName = deploymentName;
    }

    public AlertInterpretation interpret(String alertText) {
        if (alertText == null || alertText.isBlank()) {
            throw new IllegalArgumentException("El texto de la alerta no puede estar vacío");
        }

        ChatCompletionsOptions options = new ChatCompletionsOptions(List.of(
                new ChatRequestSystemMessage(SYSTEM_PROMPT),
                new ChatRequestUserMessage(alertText)));
        options.setTemperature(0.0);
        ChatCompletions completions = getClient().getChatCompletions(deploymentName, options);
        if (completions.getChoices() == null || completions.getChoices().isEmpty()
                || completions.getChoices().get(0).getMessage() == null
                || completions.getChoices().get(0).getMessage().getContent() == null) {
            throw new IllegalStateException("Azure OpenAI devolvió una respuesta sin contenido");
        }

        try {
            JsonNode result = objectMapper.readTree(completions.getChoices().get(0).getMessage().getContent());
            String severity = requiredText(result, "severity").toUpperCase();
            if (!List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(severity)) {
                throw new IllegalArgumentException("severity inválido: " + severity);
            }
            double confidence = result.path("confidenceScore").asDouble(-1);
            if (confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("confidenceScore debe estar entre 0 y 1");
            }
            if (!result.has("isCorridorBlocked") || !result.get("isCorridorBlocked").isBoolean()) {
                throw new IllegalArgumentException("isCorridorBlocked debe ser booleano");
            }
            return new AlertInterpretation(
                    result.get("isCorridorBlocked").booleanValue(),
                    requiredText(result, "affectedCorridor"),
                    severity,
                    confidence);
        } catch (Exception e) {
            log.error("Respuesta inválida de Azure OpenAI para alerta comunitaria", e);
            throw new IllegalStateException("Azure OpenAI no devolvió el JSON de alerta esperado", e);
        }
    }

    private OpenAIClient getClient() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("AZURE_OPENAI_KEY debe configurarse para interpretar alertas");
        }
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = new OpenAIClientBuilder()
                            .endpoint(endpoint)
                            .credential(new AzureKeyCredential(apiKey))
                            .buildClient();
                }
            }
        }
        return client;
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " debe ser texto");
        }
        return value.textValue();
    }

    public record AlertInterpretation(
            boolean isCorridorBlocked,
            String affectedCorridor,
            String severity,
            double confidenceScore) {
    }
}
