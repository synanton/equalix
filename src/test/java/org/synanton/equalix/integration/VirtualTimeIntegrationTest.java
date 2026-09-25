package org.synanton.equalix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.synanton.equalix.adapter.out.database.entity.TaskEntity;
import org.synanton.equalix.domain.model.TaskStatus;

class VirtualTimeIntegrationTest extends BaseIntegrationTest {

    private static final Duration DISPATCH_TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldPersistWeightedVirtualTimeThroughQueueingAndDispatch() throws Exception {
        String fairnessKey = "vt-client-" + UUID.randomUUID();
        int taskCount = 4;
        for (int taskIndex = 0; taskIndex < taskCount; taskIndex++) {
            createTask(fairnessKey, "2.0");
        }

        List<TaskEntity> dispatched = awaitAllDispatched(fairnessKey, taskCount);

        // quantum 1000 / weight 2 = 500 virtual units per task. The starting point is the system virtual time,
        // which scheduler activity from earlier tests may already have advanced, so assert relative spacing.
        double firstTag = dispatched.getFirst().getVirtualFinish();
        double lastTag = firstTag + 1500.0;
        assertThat(dispatched)
            .extracting(TaskEntity::getVirtualFinish)
            .containsExactly(firstTag, firstTag + 500.0, firstTag + 1000.0, lastTag);
        assertThat(clientVirtualTimeJpaRepository.findById(fairnessKey))
            .get()
            .extracting(entity -> List.of(entity.getVirtualTime(), entity.getVirtualFinish()))
            .isEqualTo(List.of(lastTag, lastTag));
        assertThat(schedulerVirtualClockJpaRepository.findSystemVirtualTime()).isGreaterThanOrEqualTo(lastTag);
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

    private List<TaskEntity> awaitAllDispatched(String fairnessKey, int expectedCount) throws InterruptedException {
        long deadline = System.nanoTime() + DISPATCH_TIMEOUT.toNanos();
        List<TaskEntity> tasks = List.of();
        while (System.nanoTime() < deadline) {
            tasks = taskJpaRepository.findByFairnessKeyOrderByCreatedAtAsc(fairnessKey);
            boolean allDispatched = tasks.size() == expectedCount
                && tasks.stream().allMatch(task -> task.getStatus() == TaskStatus.DISPATCHED);
            if (allDispatched) {
                return tasks;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Tasks were not dispatched within " + DISPATCH_TIMEOUT + ": " + tasks);
    }
}
