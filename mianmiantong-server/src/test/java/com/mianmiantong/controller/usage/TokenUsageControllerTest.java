package com.mianmiantong.controller.usage;

import com.mianmiantong.config.GlobalExceptionHandler;
import com.mianmiantong.service.ai.usage.TokenUsageResponse;
import com.mianmiantong.service.ai.usage.TokenUsageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.time.LocalDate;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TokenUsageControllerTest {
    private TokenUsageService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TokenUsageService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TokenUsageController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void personalEndpointUsesOnlyCurrentUserAndDefaultsToSevenDays() throws Exception {
        login(7L, 0);
        LocalDate date = LocalDate.of(2026, 9, 9);
        when(service.personal(7L, 7, null, null)).thenReturn(new TokenUsageResponse(
                "Asia/Shanghai", date.minusDays(6), date.plusDays(1),
                new TokenUsageResponse.Metrics(1, 10, 20, 30, 0, 0),
                List.of(new TokenUsageResponse.Daily(date, 1, 10, 20, 30, 0, 0)),
                List.of(new TokenUsageResponse.Group("m", 1, 10, 20, 30, 0, 0)), List.of(), List.of()));
        mvc.perform(get("/api/user/token-usage")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.summary.totalTokens").value(30))
                .andExpect(jsonPath("$.data.daily[0].inputTokens").value(10))
                .andExpect(jsonPath("$.data.models[0].key").value("m"))
                .andExpect(jsonPath("$.data.users").isEmpty());
        verify(service).personal(7L, 7, null, null);
        mvc.perform(get("/api/user/token-usage").param("userId", "8")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/user/token-usage").param("userId", "")).andExpect(status().isBadRequest());
        verifyNoMoreInteractions(service);
    }

    @Test
    void missingUserAndUnauthenticatedPrincipalCannotQueryUsage() throws Exception {
        mvc.perform(get("/api/user/token-usage")).andExpect(status().isUnauthorized());
        login("anonymousUser", 1);
        mvc.perform(get("/api/admin/token-usage")).andExpect(status().isUnauthorized());
        var authentication = new UsernamePasswordAuthenticationToken(7L, 1);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        mvc.perform(get("/api/admin/token-usage")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void regularUserCannotUseAdminEndpoint() throws Exception {
        login(7L, 0);
        mvc.perform(get("/api/admin/token-usage")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(service);
    }

    @Test
    void adminEndpointAcceptsAllFilters() throws Exception {
        login(7L, 1);
        mvc.perform(get("/api/admin/token-usage").param("days", "30").param("model", "m")
                        .param("feature", "RESUME").param("userId", "8").param("keySource", "SYSTEM"))
                .andExpect(status().isOk());
        verify(service).admin(30, "m", "RESUME", 8L, "SYSTEM");
    }

    @Test
    void rejectsMalformedParametersAndSurfacesValidationAsBadRequest() throws Exception {
        login(7L, 1);
        mvc.perform(get("/api/admin/token-usage").param("userId", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/token-usage").param("userId", "")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/token-usage").param("days", "abc")).andExpect(status().isBadRequest());
        when(service.admin(8, null, null, null, null)).thenThrow(new IllegalArgumentException("invalid days"));
        mvc.perform(get("/api/admin/token-usage").param("days", "8")).andExpect(status().isBadRequest());
    }

    private void login(Object userId, int role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, role, List.of()));
    }
}
