package org.synanton.equalix.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.synanton.equalix.domain.service.PriorityCalculatorService;
import org.synanton.equalix.domain.service.ResultPassthroughRecoveryService;
import org.synanton.equalix.domain.service.SequentialDispatcherService;

class ResultPassthroughRecoveryIntegrationTest extends BaseIntegrationTest {

    private static final String API_KEY = "test-api-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PriorityCalculatorService priorityCalculatorService;

    @Autowired
    private SequentialDispatcherService sequentialDispatcherService;

    @Autowired
    private ResultPassthroughRecoveryService resultPassthroughRecoveryService;

    @Test
    void shouldFailDependentTaskWhenPredecessorFailed() throws Exception {
        String fairnessKey = "passthrough-client-" + UUID.randomUUID();
        UUID predecessorId = createSequentialTask(fairnessKey, 1, null);
        UUID dependentId = createSequentialTask(fairnessKey, 2, predecessorId);

        priorityCalculatorService.run();
        sequentialDispatcherService.dispatch();
        mockMvc.perform(post("/api/v1/tasks/{taskId}/complete", predecessorId)
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"success": false, "error": "executor crashed"}
                    """))
            .andExpect(status().is2xxSuccessful());

        resultPassthroughRecoveryService.recover();

        mockMvc.perform(get("/api/v1/tasks/{taskId}", dependentId).header("X-API-Key", API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.lastError").value("Dependency failed: executor crashed"));
    }

    private UUID createSequentialTask(String fairnessKey, long sequenceNumber, @Nullable UUID dependsOnTaskId)
        throws Exception {
        String payload = Base64.getEncoder().encodeToString("passthrough".getBytes());
        String dependency = dependsOnTaskId == null ? "null" : "\"" + dependsOnTaskId + "\"";
        String body = """
            {
                "fairnessKey": "%s",
                "weight": 1.0,
                "payload": "%s",
                "sequential": true,
                "sequenceNumber": %d,
                "dependsOnTaskId": %s,
                "requiresPreviousResult": %s
            }
            """.formatted(fairnessKey, payload, sequenceNumber, dependency, dependsOnTaskId != null);

        String response = mockMvc.perform(post("/api/v1/tasks")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return UUID.fromString(response.replace("\"", ""));
    }
}
