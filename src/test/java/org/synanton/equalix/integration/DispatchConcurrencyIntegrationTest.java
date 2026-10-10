package org.synanton.equalix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.in.TaskCompletionPort;
import org.synanton.equalix.domain.port.in.TaskIngestionPort;
import org.synanton.equalix.domain.service.DispatcherService;
import org.synanton.equalix.domain.service.PriorityCalculatorService;
import org.synanton.equalix.domain.service.TaskTimeoutService;

/**
 * Concurrency guarantees of the write-optimized dispatch path (P1): bulk status UPDATEs,
 * per-key batched counts, and guarded completion/timeout transitions under races that a
 * single-threaded suite never produces.
 *
 * <p>What is covered live here vs in unit tests: duplicate completion and timeout-vs-completion
 * conflicts are pinned at the service level
 * ({@code CompletionHandlerServiceTest}, {@code TaskTimeoutServiceTest}); the tests below
 * prove the same guarantees end-to-end against PostgreSQL, plus the two properties only
 * concurrency can break — exactly-once dispatch across competing dispatchers, and exact
 * counter accounting after batched and raced transitions.
 */
@TestPropertySource(properties = {
    "app.queue.max-per-client-quota=0",
    "app.queue.max-tasks-in-process=500",
    "app.queue.worker-poll-size=500",
    "app.queue.max-queued-time-ms=3153600000000",
    "app.queue.task-timeout-ms=1"
})
class DispatchConcurrencyIntegrationTest extends BaseIntegrationTest {

    private static final int TENANTS = 4;
    private static final int TASKS_PER_TENANT = 50;
    private static final int DISPATCH_THREADS = 4;
    private static final byte[] PAYLOAD = "race".getBytes();

    @Autowired
    private TaskIngestionPort taskIngestion;

    @Autowired
    private TaskCompletionPort taskCompletion;

    @Autowired
    private PriorityCalculatorService priorityCalculatorService;

    @Autowired
    private DispatcherService dispatcherService;

    @Autowired
    private TaskTimeoutService taskTimeoutService;

    @Test
    void shouldDispatchEachTaskExactlyOnceUnderConcurrentDispatchers() throws Exception {
        List<String> tenants = tenants();
        tenants.forEach(tenant -> ingest(tenant, TASKS_PER_TENANT));
        drainCalculator();
        int total = TENANTS * TASKS_PER_TENANT;

        List<UUID> sent = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> sent.add(invocation.getArgument(0)))
            .when(remoteExecutor).send(any(), any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(DISPATCH_THREADS);
        List<Callable<Void>> ticks = IntStream.range(0, 40)
            .mapToObj(i -> (Callable<Void>) () -> {
                dispatcherService.dispatch();
                return null;
            })
            .toList();
        try {
            pool.invokeAll(ticks);
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();
        }

        // Exactly-once dispatch: no task sent twice, none lost.
        assertThat(sent).hasSize(total);
        synchronized (sent) {
            assertThat(new HashSet<>(sent)).hasSize(total);
        }
        // Batched accounting: every dispatched task holds exactly one slot.
        assertThat(totalInFlight()).isEqualTo(total);

        // Drain through the completion port; counters must return to zero exactly.
        new ArrayList<>(sent).forEach(id -> taskCompletion.completeTask(id, true, PAYLOAD, null));
        assertThat(totalInFlight()).isZero();
        tenants.forEach(tenant -> assertThat(inFlightFor(tenant)).isZero());
    }

    @Test
    void shouldKeepCompletionIdempotentUnderDuplicateDeliveries() {
        String tenant = tenants().get(0);
        ingest(tenant, 1);
        drainCalculator();
        captureSends();
        dispatcherService.dispatch();

        UUID id = sentIds().get(0);
        taskCompletion.completeTask(id, true, PAYLOAD, null);
        taskCompletion.completeTask(id, true, PAYLOAD, null);

        assertThat(taskJpaRepository.findById(id)).isPresent();
        assertThat(taskJpaRepository.findById(id).get().getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(totalInFlight()).isZero();
    }

    @Test
    void shouldNotDoubleReleaseWhenTimeoutFollowsCompletion() throws InterruptedException {
        String tenant = tenants().get(0);
        ingest(tenant, 1);
        drainCalculator();
        captureSends();
        dispatcherService.dispatch();

        UUID id = sentIds().get(0);
        taskCompletion.completeTask(id, true, PAYLOAD, null);
        // Let the 1 ms timeout lapse, then sweep: the SUCCEEDED row must be untouched
        // and the already-released slot must not be released again.
        Thread.sleep(100);
        taskTimeoutService.expireTimedOutTasks();

        assertThat(taskJpaRepository.findById(id).get().getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(totalInFlight()).isZero();
    }

    @Test
    void shouldAggregateCountsPerKeyOnBatchedDispatch() {
        String tenant = tenants().get(0);
        int tasks = 50;
        ingest(tenant, tasks);
        drainCalculator();
        captureSends();

        dispatcherService.dispatch();

        assertThat(sentIds()).hasSize(tasks);
        assertThat(inFlightFor(tenant)).isEqualTo(tasks);

        new ArrayList<>(sentIds()).forEach(id -> taskCompletion.completeTask(id, true, PAYLOAD, null));
        assertThat(inFlightFor(tenant)).isZero();
    }

    private List<String> tenants() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        return IntStream.range(0, TENANTS).mapToObj(i -> "race-" + runId + "-" + i).toList();
    }

    private void ingest(String tenant, int count) {
        IntStream.range(0, count).forEach(
            i -> taskIngestion.createTask(tenant, BigDecimal.ONE, PAYLOAD, false, null, null, false));
    }

    private void drainCalculator() {
        for (int i = 0; i < 10; i++) {
            priorityCalculatorService.run();
        }
    }

    private final List<UUID> captured = Collections.synchronizedList(new ArrayList<>());

    private void captureSends() {
        captured.clear();
        doAnswer(invocation -> captured.add(invocation.getArgument(0)))
            .when(remoteExecutor).send(any(), any(), any());
    }

    private List<UUID> sentIds() {
        synchronized (captured) {
            return new ArrayList<>(captured);
        }
    }

    private long totalInFlight() {
        return clientCountsJpaRepository.findAll().stream()
            .mapToLong(entity -> entity.getInFlightCount())
            .sum();
    }

    private int inFlightFor(String tenant) {
        return clientCountsJpaRepository.findById(tenant)
            .map(entity -> entity.getInFlightCount())
            .orElse(0);
    }
}
