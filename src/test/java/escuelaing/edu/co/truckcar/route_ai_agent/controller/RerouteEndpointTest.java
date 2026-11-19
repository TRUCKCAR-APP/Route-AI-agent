package escuelaing.edu.co.truckcar.route_ai_agent.controller;

import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.PlaceResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.dto.response.RerouteResponse;
import escuelaing.edu.co.truckcar.route_ai_agent.routing.HerePlacesClient;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.GlobalExceptionHandler;
import escuelaing.edu.co.truckcar.route_ai_agent.exception.RoutingProviderUnavailableException;
import escuelaing.edu.co.truckcar.route_ai_agent.service.IRouteAgentService;
import escuelaing.edu.co.truckcar.route_ai_agent.service.RerouteService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/v1/routes/reroute con el controlador y el manejador de errores reales. */
class RerouteEndpointTest {

    private static final String BODY = """
            {"originLatitude":4.70,"originLongitude":-74.05,"destinationLatitude":5.53,"destinationLongitude":-73.36,
             "heightMeters":4.1,"grossWeightTons":32,"truckType":"TRACTOMULA_3S3",
             "avoid":[{"latitude":4.75,"longitude":-74.04}]}
            """;

    private final RerouteService reroute = mock(RerouteService.class);
    private final HerePlacesClient places = mock(HerePlacesClient.class);
    private final MockMvc mvc;

    RerouteEndpointTest() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new RouteAgentController(mock(IRouteAgentService.class), reroute, places))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void returnsTheAlternativesFromHere() throws Exception {
        when(reroute.reroute(any())).thenReturn(new RerouteResponse("HERE", List.of(
                new RerouteResponse.Option(52000, 3900, List.of(new double[] {4.7, -74.05}), List.of(), List.of(), 0))));
        mvc.perform(post("/api/v1/routes/reroute").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("HERE"))
                .andExpect(jsonPath("$.routes[0].durationSeconds").value(3900))
                .andExpect(jsonPath("$.routes[0].geometry[0][0]").value(4.7));
    }

    @Test
    void anIncompleteTruckOrPositionIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/routes/reroute").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("\"heightMeters\":4.1,", "")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/routes/reroute").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("4.70", "120")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reroute);
    }

    @Test
    void placesAreSearchedByNameNearThePosition() throws Exception {
        when(places.search("universidad", 4.7, -74.05, 5)).thenReturn(List.of(
                new PlaceResponse("p1", "Escuela Colombiana de Ingeniería", "AK 45 #205-59", "Universidad", 4.78, -74.04, 14230.0)));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/routes/places")
                        .param("q", " universidad ").param("lat", "4.7").param("lng", "-74.05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Escuela Colombiana de Ingeniería"))
                .andExpect(jsonPath("$[0].category").value("Universidad"));
    }

    @Test
    void aTooShortSearchIsABadRequest() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/routes/places").param("q", "a"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(places);
    }

    @Test
    void hereUnavailableIs503SoTheAppFallsBack() throws Exception {
        when(reroute.reroute(any())).thenThrow(new RoutingProviderUnavailableException("HERE no está disponible"));
        mvc.perform(post("/api/v1/routes/reroute").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());
    }
}
