package com.finflow.transaction_service.api.admin;

import com.finflow.transaction_service.config.SecurityConfig;
import com.finflow.transaction_service.domain.outbox.OutboxEvent;
import com.finflow.transaction_service.exception.OutboxEventNotFoundException;
import com.finflow.transaction_service.exception.OutboxEventNotRetryableException;
import com.finflow.transaction_service.service.OutboxAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The outbox operator endpoints, through the real {@link SecurityConfig}. */
@WebMvcTest(OutboxAdminController.class)
@Import(SecurityConfig.class)
class OutboxAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OutboxAdminService adminService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor withRole(String role) {
        return jwt()
                .jwt(token -> token.subject("operator-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static OutboxEvent exhaustedEvent() {
        OutboxEvent event = new OutboxEvent(
                "TRANSACTION", UUID.randomUUID(), "TRANSFER_COMPLETED", "{}");
        event.markFailed("Kafka unavailable", 1);
        return event;
    }

    @Test
    void shouldRequireAToken() throws Exception {
        mockMvc.perform(get("/admin/outbox/failed"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(adminService);
    }

    @Test
    void shouldForbidUsersWithoutTheAdminRole() throws Exception {
        mockMvc.perform(get("/admin/outbox/failed").with(withRole("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/outbox/{id}/retry", UUID.randomUUID()).with(withRole("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminService);
    }

    @Test
    void shouldListExhaustedEventsWithoutTheirPayload() throws Exception {
        OutboxEvent event = exhaustedEvent();
        when(adminService.findExhausted(50)).thenReturn(List.of(event));

        mockMvc.perform(get("/admin/outbox/failed").with(withRole("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(event.getId().toString()))
                .andExpect(jsonPath("$[0].attempts").value(1))
                .andExpect(jsonPath("$[0].lastError").value("Kafka unavailable"))
                .andExpect(jsonPath("$[0].failedAt").exists())
                .andExpect(jsonPath("$[0].payload").doesNotExist());
    }

    @Test
    void shouldRejectAnOutOfRangeLimit() throws Exception {
        mockMvc.perform(get("/admin/outbox/failed").param("limit", "0").with(withRole("ADMIN")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(adminService);
    }

    @Test
    void shouldRetryAnExhaustedEventOnBehalfOfTheOperator() throws Exception {
        OutboxEvent event = exhaustedEvent();
        event.requeue();
        when(adminService.retry(event.getId(), "operator-1")).thenReturn(event);

        mockMvc.perform(post("/admin/outbox/{id}/retry", event.getId()).with(withRole("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(event.getId().toString()))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.failedAt").doesNotExist());

        verify(adminService).retry(eq(event.getId()), eq("operator-1"));
    }

    @Test
    void shouldReturn404ForAnUnknownEvent() throws Exception {
        UUID id = UUID.randomUUID();
        when(adminService.retry(eq(id), any())).thenThrow(new OutboxEventNotFoundException(id));

        mockMvc.perform(post("/admin/outbox/{id}/retry", id).with(withRole("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn409ForAnEventThatIsNotExhausted() throws Exception {
        UUID id = UUID.randomUUID();
        when(adminService.retry(eq(id), any())).thenThrow(new OutboxEventNotRetryableException(id));

        mockMvc.perform(post("/admin/outbox/{id}/retry", id).with(withRole("ADMIN")))
                .andExpect(status().isConflict());
    }
}
