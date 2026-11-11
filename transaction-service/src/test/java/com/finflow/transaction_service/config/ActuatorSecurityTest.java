package com.finflow.transaction_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks the real {@link SecurityConfig} rules for the actuator: the
 * Prometheus scrape endpoint is anonymous, other metrics need a JWT. Stub
 * handlers stand in for the actuator endpoints, which the MVC slice does
 * not load.
 */
@WebMvcTest(ActuatorSecurityTest.StubActuatorController.class)
@Import({SecurityConfig.class, ActuatorSecurityTest.StubActuatorController.class})
class ActuatorSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldAllowAnonymousPrometheusScrapes() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRequireAuthenticationForOtherMetrics() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    static class StubActuatorController {

        @GetMapping({"/actuator/prometheus", "/actuator/metrics"})
        String metrics() {
            return "ok";
        }
    }
}
