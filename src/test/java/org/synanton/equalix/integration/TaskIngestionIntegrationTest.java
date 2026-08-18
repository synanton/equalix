package org.synanton.equalix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.synanton.equalix.domain.model.TaskStatus;

class TaskIngestionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldCreateTaskViaRestAndFindItInDatabase() throws Exception {
        byte[] payload = "test-payload".getBytes();
        String payloadBase64 = Base64.getEncoder().encodeToString(payload);
        String requestBody = """
            {
                "fairnessKey": "integration-client",
                "weight": 1.5,
                "payload": "%s",
                "sequential": false,
                "requiresPreviousResult": false
            }
            """.formatted(payloadBase64);

        MvcResult result = mockMvc.perform(post("/api/v1/tasks")
                .header("X-API-Key", "test-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated())
            .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        UUID taskId = UUID.fromString(responseBody.replace("\"", ""));

        assertThat(taskJpaRepository.findById(taskId))
            .isPresent()
            .get()
            .satisfies(task -> {
                assertThat(task.getFairnessKey()).isEqualTo("integration-client");
                assertThat(task.getWeight()).isEqualTo(new BigDecimal("1.5000"));
                assertThat(task.getStatus()).isEqualTo(TaskStatus.RECEIVED);
                assertThat(task.isSequential()).isFalse();
                assertThat(task.getPayload()).isEqualTo(payload);
                assertThat(task.getCreatedAt()).isNotNull();
            });
    }

    @Test
    void shouldRejectTaskWithBlankFairnessKey() throws Exception {
        String payloadBase64 = Base64.getEncoder().encodeToString("test".getBytes());
        String requestBody = """
            {
                "fairnessKey": "",
                "weight": 1.0,
                "payload": "%s",
                "sequential": false,
                "requiresPreviousResult": false
            }
            """.formatted(payloadBase64);

        mockMvc.perform(post("/api/v1/tasks")
                .header("X-API-Key", "test-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isBadRequest());
    }
}
