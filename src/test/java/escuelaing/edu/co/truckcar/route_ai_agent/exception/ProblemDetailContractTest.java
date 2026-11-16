package escuelaing.edu.co.truckcar.route_ai_agent.exception;

import escuelaing.edu.co.truckcar.route_ai_agent.config.JacksonConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Errores RFC 7807 con los campos de antes, serializados con el ObjectMapper de la aplicación (JacksonConfig). */
class ProblemDetailContractTest {

    @RestController
    static class ProbeController {
        record Body(@NotNull(message = "El id de la carga es obligatorio") Long loadId) { }

        @GetMapping("/routes/forbidden")
        String forbidden() {
            throw new RouteAccessDeniedException("El rol GENERADOR no puede planear rutas", false);
        }

        @PostMapping("/routes/validate")
        String validate(@Valid @RequestBody Body body) {
            return "ok";
        }

        @GetMapping("/routes/boom")
        String boom() {
            throw new IllegalStateException("detalle interno");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new JacksonConfig().objectMapper()))
            .build();

    @Test
    void anErrorHasTheStandardFieldsAndTheOldOnesAtTheRoot() throws Exception {
        mvc.perform(get("/routes/forbidden").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.type").value("https://truckcar.co/errors/forbidden"))
                .andExpect(jsonPath("$.title").value("Operación no permitida"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.instance").value("/routes/forbidden"))
                .andExpect(jsonPath("$.detail").value("El rol GENERADOR no puede planear rutas"))
                .andExpect(jsonPath("$.message").value("El rol GENERADOR no puede planear rutas"))
                .andExpect(jsonPath("$.error").value("Operación no permitida"))
                .andExpect(jsonPath("$.properties").doesNotExist());
    }

    @Test
    void problemJsonOnlyForWhoAsksForIt() throws Exception {
        mvc.perform(get("/routes/forbidden").accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/routes/forbidden")).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void aValidationErrorListsTheFieldsInDetailsAndErrors() throws Exception {
        mvc.perform(post("/routes/validate").accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.loadId").value("El id de la carga es obligatorio"))
                .andExpect(jsonPath("$.errors[0].field").value("loadId"))
                .andExpect(jsonPath("$.errors[0].message").value("El id de la carga es obligatorio"))
                .andExpect(jsonPath("$.message").value("El id de la carga es obligatorio"));
    }

    @Test
    void anUnexpectedErrorDoesNotLeakInternals() throws Exception {
        mvc.perform(get("/routes/boom").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://truckcar.co/errors/internal-error"))
                .andExpect(content().string(not(containsString("detalle interno"))));
    }
}
