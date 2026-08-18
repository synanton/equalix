package org.synanton.equalix.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.synanton.equalix.adapter.out.database.entity.TaskEntity;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.service.PriorityCalculatorService;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SequentialExecutionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PriorityCalculatorService priorityCalculatorService;

    @Test
    void shouldIngestSequentialTasksWithReceivedStatus() throws Exception {
        String payload = Base64.getEncoder().encodeToString("seq-payload".getBytes());
        String clientKey = "seq-client-" + UUID.randomUUID();

        for (int seqNum = 1; seqNum <= 3; seqNum++) {
            String body = """
                    {
                        "fairnessKey": "%s",
                        "weight": 1.0,
                        "payload": "%s",
                        "sequential": true,
                        "sequenceNumber": %d,
                        "requiresPreviousResult": false
                    }
                    """.formatted(clientKey, payload, seqNum);

            mockMvc.perform(post("/api/v1/tasks")
                            .header("X-API-Key", "test-api-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        List<TaskEntity> tasks = taskJpaRepository.findByFairnessKeyOrderByCreatedAtAsc(clientKey);
        assertThat(tasks).hasSize(3);
        assertThat(tasks).allMatch(task -> task.getStatus() == TaskStatus.RECEIVED);
        assertThat(tasks).allMatch(TaskEntity::isSequential);
        assertThat(sequenceStateJpaRepository.findById(clientKey)).isPresent();
    }

    @Test
    void shouldTransitionTasksToQueuedAfterPriorityCalculation() throws Exception {
        String payload = Base64.getEncoder().encodeToString("data".getBytes());
        String clientKey = "priority-client-" + UUID.randomUUID();

        String body = """
                {
                    "fairnessKey": "%s",
                    "weight": 1.0,
                    "payload": "%s",
                    "sequential": false,
                    "requiresPreviousResult": false
                }
                """.formatted(clientKey, payload);

        mockMvc.perform(post("/api/v1/tasks")
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        priorityCalculatorService.run();

        List<TaskEntity> tasks = taskJpaRepository.findByFairnessKeyOrderByCreatedAtAsc(clientKey);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(tasks.getFirst().getPriority()).isNotNull();
    }
}
