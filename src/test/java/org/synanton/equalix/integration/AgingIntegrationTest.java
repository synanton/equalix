package org.synanton.equalix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.synanton.equalix.adapter.out.database.entity.TaskEntity;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.in.TaskIngestionPort;
import org.synanton.equalix.domain.service.DispatcherService;
import org.synanton.equalix.domain.service.PriorityCalculatorService;

/**
 * EQX-4: aging reorders dispatch against PostgreSQL. The candidate pool is smaller than the backlog, so the
 * oldest tasks can only be reached through the arrival-ordered candidate query.
 */
@TestPropertySource(properties = {
    "app.queue.aging.policy=linear",
    "app.queue.aging.lambda=1000",
    "app.queue.aging.candidate-pool-size=2",
    "app.queue.max-per-client-quota=0",
    "app.queue.max-tasks-in-process=5",
    "app.queue.max-queued-time-ms=3153600000000"
})
class AgingIntegrationTest extends BaseIntegrationTest {

    private static final int TASKS_PER_TENANT = 5;
    private static final byte[] PAYLOAD = "aging".getBytes();

    @Autowired
    private TaskIngestionPort taskIngestion;

    @Autowired
    private PriorityCalculatorService priorityCalculatorService;

    @Autowired
    private DispatcherService dispatcherService;

    @Test
    void shouldDispatchLongWaitingTenantAheadOfEqualPriorityFreshTasks() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        List<UUID> waitingTasks = ingest("waiting-" + runId);
        priorityCalculatorService.run();
        clock.advance(Duration.ofSeconds(60));
        List<UUID> freshTasks = ingest("fresh-" + runId);
        priorityCalculatorService.run();
        List<UUID> sent = new ArrayList<>();
        doAnswer(invocation -> sent.add(invocation.getArgument(0)))
            .when(remoteExecutor).send(any(), any(), any());

        dispatcherService.dispatch();

        // Both tenants hold tags 1000..5000. Without aging they would interleave; 60 s of linear aging
        // (60,000 units) moves every waiting task ahead, including the two outside the priority-ordered pool.
        assertThat(sent).containsExactlyElementsOf(waitingTasks);
        assertThat(taskJpaRepository.findAllById(freshTasks))
            .extracting(TaskEntity::getStatus)
            .containsOnly(TaskStatus.QUEUED);
    }

    private List<UUID> ingest(String tenant) {
        return IntStream.range(0, TASKS_PER_TENANT)
            .mapToObj(index -> {
                clock.advance(Duration.ofMillis(1));
                return taskIngestion.createTask(tenant, BigDecimal.ONE, PAYLOAD, false, null, null, false);
            })
            .map(Task::getId)
            .toList();
    }
}
