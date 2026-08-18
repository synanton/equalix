package org.synanton.equalix.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.synanton.equalix.config.ApiKeyAuthFilter;
import org.synanton.equalix.config.SecurityConfig;
import org.synanton.equalix.config.properties.SecurityProperties;
import org.synanton.equalix.domain.TaskNotFoundException;
import org.synanton.equalix.domain.port.in.TaskCompletionPort;
import org.synanton.equalix.domain.port.in.TaskIngestionPort;
import org.synanton.equalix.domain.port.in.TaskManagementPort;

@WebMvcTest(controllers = {
    TaskManagementController.class, TaskCompletionController.class, TaskIngestionController.class})
@Import({
    GlobalExceptionHandler.class,
    SecurityConfig.class,
    ApiKeyAuthFilter.class,
    GlobalExceptionHandlerTest.FixedClockConfig.class,
    GlobalExceptionHandlerTest.SecurityPropsConfig.class
})
@TestPropertySource(properties = "app.security.api-key=test-api-key")
class GlobalExceptionHandlerTest {

    private static final Instant FIXED = Instant.parse("2026-01-01T12:00:00Z");
    private static final String API_KEY = "test-api-key";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskManagementPort managementPort;

    @MockBean
    private TaskCompletionPort completionPort;

    @MockBean
    private TaskIngestionPort ingestionPort;

    @Test
    void shouldReturn404WithStructuredBodyWhenTaskNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(managementPort.getTask(id)).thenThrow(new TaskNotFoundException(id));

        mockMvc.perform(withApiKey(get("/api/v1/tasks/{id}", id)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Task not found: " + id))
            .andExpect(jsonPath("$.timestamp").value(FIXED.toString()));
    }

    @Test
    void shouldReturn400WithFieldErrorsForInvalidCreateRequest() throws Exception {
        String body = """
            {
                "fairnessKey": "",
                "weight": -1,
                "payload": null,
                "sequential": false,
                "requiresPreviousResult": false
            }
            """;

        mockMvc.perform(withApiKey(post("/api/v1/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void shouldReturn400WhenCompletionMarkedFailedButErrorMissing() throws Exception {
        UUID id = UUID.randomUUID();
        String body = """
            {
                "success": false,
                "result": null,
                "error": null
            }
            """;

        mockMvc.perform(withApiKey(post("/api/v1/tasks/{id}/complete", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void shouldAcceptCompletionWithErrorWhenFailed() throws Exception {
        UUID id = UUID.randomUUID();
        String body = """
            {
                "success": false,
                "result": null,
                "error": "downstream failed"
            }
            """;

        mockMvc.perform(withApiKey(post("/api/v1/tasks/{id}/complete", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)))
            .andExpect(status().isOk());
    }

    @Test
    void shouldReturn400ForIllegalArgumentException() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalArgumentException("invalid state"))
            .when(completionPort).completeTask(eq(id), any(Boolean.class), any(), any());
        String body = """
            {
                "success": true,
                "result": null,
                "error": null
            }
            """;

        mockMvc.perform(withApiKey(post("/api/v1/tasks/{id}/complete", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("invalid state"));
    }

    @Test
    void shouldRejectRequestWithoutApiKey() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/tasks/{id}", id))
            .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder withApiKey(MockHttpServletRequestBuilder request) {
        return request.header("X-API-Key", API_KEY);
    }

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        public Clock testClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }

    @TestConfiguration
    @EnableConfigurationProperties(SecurityProperties.class)
    static class SecurityPropsConfig {
    }
}
