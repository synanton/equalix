package org.synanton.equalix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.synanton.equalix.adapter.out.database.entity.TaskEntity;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.service.DispatcherService;
import org.synanton.equalix.domain.service.PriorityCalculatorService;

class VirtualTimeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PriorityCalculatorService priorityCalculatorService;

    @Autowired
    private DispatcherService dispatcherService;

    @Test
    void shouldPersistWeightedVirtualTimeThroughQueueingAndDispatch() throws Exception {
        String fairnessKey = "vt-client-" + UUID.randomUUID();
        int taskCount = 4;
        for (int taskIndex = 0; taskIndex < taskCount; taskIndex++) {
            createTask(fairnessKey, "2.0");
        }

        priorityCalculatorService.run();
        dispatcherService.dispatch();

        // quantum 1000 / weight 2 = 500 virtual units per task, starting from V = 0.
        List<TaskEntity> dispatched = taskJpaRepository.findByFairnessKeyOrderByCreatedAtAsc(fairnessKey);
        assertThat(dispatched)
            .extracting(TaskEntity::getStatus, TaskEntity::getVirtualFinish)
            .containsExactly(
                tuple(TaskStatus.DISPATCHED, 500.0),
                tuple(TaskStatus.DISPATCHED, 1000.0),
                tuple(TaskStatus.DISPATCHED, 1500.0),
                tuple(TaskStatus.DISPATCHED, 2000.0));
        assertThat(clientVirtualTimeJpaRepository.findById(fairnessKey))
            .get()
            .extracting(entity -> List.of(entity.getVirtualTime(), entity.getVirtualFinish()))
            .isEqualTo(List.of(2000.0, 2000.0));
        assertThat(schedulerVirtualClockJpaRepository.findSystemVirtualTime()).isEqualTo(2000.0);
    }

    private void createTask(String fairnessKey, String weight) throws Exception {
        String payload = Base64.getEncoder().encodeToString("vt-payload".getBytes());
        String body = """
            {
                "fairnessKey": "%s",
                "weight": %s,
                "payload": "%s",
                "sequential": false,
                "requiresPreviousResult": false
            }
            """.formatted(fairnessKey, weight, payload);

        mockMvc.perform(post("/api/v1/tasks")
                .header("X-API-Key", "test-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());
    }
}
