package org.synanton.equalix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.port.in.TaskCompletionPort;
import org.synanton.equalix.domain.port.in.TaskIngestionPort;
import org.synanton.equalix.domain.service.DispatcherService;
import org.synanton.equalix.domain.service.PriorityCalculatorService;
import org.synanton.equalix.fairness.FairnessStatistics;

/**
 * EQX-1: validates the long-term weighted fairness invariant (mathematical invariants §5, §6, §26).
 *
 * <p>Three tenants with weights 1, 2 and 7 are kept continuously backlogged while the real priority calculator
 * and dispatcher run against PostgreSQL. Each tick dispatches up to {@code max-tasks-in-process} tasks; every
 * dispatched task is completed through the completion port and replaced by a new task of the same tenant, so
 * each tenant always has {@value #BACKLOG_PER_TENANT} tasks waiting.
 *
 * <p>Conditions follow §5: quotas are off, adaptive RPS is off, and anti-starvation promotion is pushed out of
 * reach (the fixed test clock is behind database time, so every task would otherwise look starved).
 */
@Slf4j
@TestPropertySource(properties = {
    "app.queue.max-per-client-quota=0",
    "app.queue.max-tasks-in-process=" + ProportionalFairnessIntegrationTest.DISPATCH_BATCH_SIZE,
    "app.queue.worker-poll-size=200",
    "app.queue.max-queued-time-ms=3153600000000"
})
class ProportionalFairnessIntegrationTest extends BaseIntegrationTest {

    static final int DISPATCH_BATCH_SIZE = 20;
    /**
     * Empirical bound: the observed dispatch count of any tenant deviates from its weighted share by at most this
     * many tasks in any window, i.e. {@code ε_max(W) <= MAX_DISPLACED_TASKS / |W|}.
     */
    private static final double MAX_DISPLACED_TASKS = 2.0;
    private static final int[] MEASURED_WINDOWS = {10, 25, 100, 1_000, 10_000};
    private static final int BACKLOG_PER_TENANT = 2 * DISPATCH_BATCH_SIZE;
    private static final int DISPATCH_WINDOW = 10_000;
    private static final byte[] PAYLOAD = "fairness".getBytes();

    @Autowired
    private TaskIngestionPort taskIngestion;

    @Autowired
    private TaskCompletionPort taskCompletion;

    @Autowired
    private PriorityCalculatorService priorityCalculatorService;

    @Autowired
    private DispatcherService dispatcherService;

    private final List<UUID> sentTaskIds = new ArrayList<>();
    private final Map<UUID, String> tenantByTaskId = new HashMap<>();

    @BeforeEach
    void captureDispatches() {
        doAnswer(invocation -> sentTaskIds.add(invocation.getArgument(0)))
            .when(remoteExecutor).send(any(), any(), any());
    }

    @Test
    void shouldConvergeToWeightedSharesWithoutInFlightPressure() {
        Map<String, BigDecimal> weights = tenantWeights();

        List<String> dispatches = runBackloggedExperiment(weights, false);

        FairnessStatistics statistics = new FairnessStatistics(weights);
        report("no in-flight pressure", statistics, dispatches);
        assertWithinEmpiricalBound(statistics, dispatches);
    }

    @Test
    void shouldConvergeToWeightedSharesWithInFlightPressure() {
        Map<String, BigDecimal> weights = tenantWeights();

        List<String> dispatches = runBackloggedExperiment(weights, true);

        FairnessStatistics statistics = new FairnessStatistics(weights);
        report("with in-flight pressure", statistics, dispatches);
        assertWithinEmpiricalBound(statistics, dispatches);
    }

    /**
     * Runs ticks of priority calculation and dispatch until {@link #DISPATCH_WINDOW} tasks have been dispatched.
     *
     * @param withInFlightPressure when true, a tick's dispatches stay in flight through the next priority
     *     calculation, so the CMS pressure term {@code p * F̂_k / w_k} is non-zero; otherwise they complete within
     *     the tick and only virtual time orders the queue
     * @return fairness key of every dispatched task, in dispatch order
     */
    private List<String> runBackloggedExperiment(Map<String, BigDecimal> weights, boolean withInFlightPressure) {
        for (int round = 0; round < BACKLOG_PER_TENANT; round++) {
            weights.forEach(this::ingest);
        }

        List<String> dispatchSequence = new ArrayList<>();
        List<UUID> inFlight = List.of();
        while (dispatchSequence.size() < DISPATCH_WINDOW) {
            priorityCalculatorService.run();
            if (withInFlightPressure) {
                complete(inFlight);
            }

            int sentBefore = sentTaskIds.size();
            dispatcherService.dispatch();
            List<UUID> dispatched = List.copyOf(sentTaskIds.subList(sentBefore, sentTaskIds.size()));
            assertThat(dispatched).as("each tick must dispatch while tenants are backlogged").isNotEmpty();

            if (withInFlightPressure) {
                inFlight = dispatched;
            } else {
                complete(dispatched);
            }
            for (UUID taskId : dispatched) {
                String tenant = tenantByTaskId.get(taskId);
                dispatchSequence.add(tenant);
                ingest(tenant, weights.get(tenant));
            }
        }
        return dispatchSequence.subList(0, DISPATCH_WINDOW);
    }

    private void ingest(String tenant, BigDecimal weight) {
        Task task = taskIngestion.createTask(tenant, weight, PAYLOAD, false, null, null, false);
        tenantByTaskId.put(task.getId(), tenant);
    }

    private void complete(List<UUID> taskIds) {
        taskIds.forEach(taskId -> taskCompletion.completeTask(taskId, true, null, null));
    }

    private static Map<String, BigDecimal> tenantWeights() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        weights.put("tenantA-" + runId, new BigDecimal("1"));
        weights.put("tenantB-" + runId, new BigDecimal("2"));
        weights.put("tenantC-" + runId, new BigDecimal("7"));
        return weights;
    }

    private static void assertWithinEmpiricalBound(FairnessStatistics statistics, List<String> dispatches) {
        for (int window : MEASURED_WINDOWS) {
            double bound = MAX_DISPLACED_TASKS / window;
            assertThat(statistics.prefixMaxError(dispatches, window))
                .as("prefix eps_max over W=%d", window)
                .isLessThanOrEqualTo(bound);
            assertThat(statistics.slidingMaxError(dispatches, window))
                .as("sliding eps_max over W=%d", window)
                .isLessThanOrEqualTo(bound);
        }
    }

    private static void report(String scenario, FairnessStatistics statistics, List<String> dispatches) {
        log.info("EQX-1 [{}] expected shares {}", scenario, statistics.expectedShares());
        for (int window : MEASURED_WINDOWS) {
            log.info("EQX-1 [{}] W={} shares={} prefix eps_max={} sliding eps_max={}",
                scenario, window, statistics.observedShares(dispatches, window),
                statistics.prefixMaxError(dispatches, window),
                statistics.slidingMaxError(dispatches, window));
        }
    }
}
