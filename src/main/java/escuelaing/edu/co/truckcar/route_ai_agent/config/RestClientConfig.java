package escuelaing.edu.co.truckcar.route_ai_agent.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Builder autoconfigurado: cada llamada propaga traceparent y X-Correlation-Id y se mide (http.client.requests).
     * Las respuestas de Community-Alerts y Routing-Restrictions traen más campos que los DTO cliente; el ObjectMapper de
     * la aplicación los rechaza (FAIL_ON_UNKNOWN_PROPERTIES), así que el RestClient usa una copia que los ignora.
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder, ObjectMapper objectMapper) {
        ObjectMapper lenient = objectMapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return builder
                .messageConverters(converters -> converters.replaceAll(converter ->
                        converter instanceof MappingJackson2HttpMessageConverter
                                ? new MappingJackson2HttpMessageConverter(lenient)
                                : converter))
                .build();
    }
}
