package escuelaing.edu.co.truckcar.route_ai_agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

import java.time.Duration;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Interpretación con Amazon Bedrock, API Converse (TRUCKCAR_AI_PROVIDER=bedrock). Resilience4j: instancia "bedrock".
 * <ul>
 *   <li>Modelo: TRUCKCAR_BEDROCK_MODEL_ID (por defecto amazon.nova-micro-v1:0). Converse usa el mismo formato de
 *       mensajes para Nova, Claude o Llama: cambiar de modelo no cambia el código.</li>
 *   <li>Credenciales: {@link DefaultCredentialsProvider}. En EKS con AWS Academy Learner Lab, el LabRole del nodo; en
 *       un clúster con IRSA o Pod Identity, el rol del pod. Ninguna clave en la configuración.</li>
 *   <li>El SDK no reintenta por su cuenta: un throttling (429) llega de inmediato a Resilience4j, que no lo reintenta
 *       (TransientModelFailure), y la alerta la clasifican las reglas.</li>
 * </ul>
 */
@Service
public class BedrockAlertInterpreter extends ModelAlertInterpreter {

    public static final String SOURCE = "BEDROCK";
    static final String RESILIENCE_INSTANCE = "bedrock";

    private final String modelId;
    private final String region;
    private volatile BedrockRuntimeClient client;

    @Autowired
    public BedrockAlertInterpreter(
            ObjectMapper objectMapper,
            RuleBasedAlertInterpreter rules,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            TimeLimiterRegistry timeLimiterRegistry,
            @Value("${truckcar.ai.bedrock.model-id:amazon.nova-micro-v1:0}") String modelId,
            @Value("${truckcar.ai.bedrock.region:us-east-1}") String region) {
        this(objectMapper, rules, circuitBreakerRegistry, retryRegistry, timeLimiterRegistry, modelId, region, null);
    }

    /** Para pruebas: un cliente ya construido (un mock), sin credenciales ni red. */
    BedrockAlertInterpreter(ObjectMapper objectMapper, RuleBasedAlertInterpreter rules,
                            CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry,
                            TimeLimiterRegistry timeLimiterRegistry, String modelId, String region,
                            BedrockRuntimeClient client) {
        super(objectMapper, rules, circuitBreakerRegistry, retryRegistry, timeLimiterRegistry, RESILIENCE_INSTANCE, "bedrock");
        this.modelId = modelId;
        this.region = region;
        this.client = client;
    }

    @Override
    protected String source() {
        return SOURCE;
    }

    /** Las credenciales se resuelven en la llamada: si faltan, el error cae al fallback como cualquier otro. */
    @Override
    protected boolean isConfigured() {
        return modelId != null && !modelId.isBlank() && region != null && !region.isBlank();
    }

    @Override
    protected String callModel(String systemPrompt, String userMessage) {
        ConverseResponse response = getClient().converse(ConverseRequest.builder()
                .modelId(modelId)
                .system(SystemContentBlock.fromText(systemPrompt))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(userMessage))
                        .build())
                .inferenceConfig(InferenceConfiguration.builder().temperature(0f).maxTokens(150).build())
                .build());
        if (response == null || response.output() == null || response.output().message() == null) {
            throw new InvalidModelResponseException("Bedrock devolvió una respuesta sin mensaje", null);
        }
        String text = response.output().message().content().stream()
                .map(ContentBlock::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
        if (text.isBlank()) {
            throw new InvalidModelResponseException("Bedrock devolvió una respuesta sin texto", null);
        }
        return text;
    }

    private BedrockRuntimeClient getClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = BedrockRuntimeClient.builder()
                            .region(Region.of(region))
                            .credentialsProvider(DefaultCredentialsProvider.create())
                            .overrideConfiguration(c -> c
                                    // Los reintentos los controla Resilience4j: el SDK no reintenta por su cuenta.
                                    .retryStrategy(AwsRetryStrategy.doNotRetry())
                                    .apiCallAttemptTimeout(Duration.ofSeconds(4))
                                    .apiCallTimeout(Duration.ofSeconds(5)))
                            .build();
                }
            }
        }
        return client;
    }
}
