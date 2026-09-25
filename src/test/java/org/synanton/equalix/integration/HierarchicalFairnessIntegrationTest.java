package org.synanton.equalix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
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
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.port.in.TaskCompletionPort;
import org.synanton.equalix.domain.port.in.TaskIngestionPort;
import org.synanton.equalix.domain.service.DispatcherService;
import org.synanton.equalix.domain.service.PriorityCalculatorService;
import org.synanton.equalix.domain.service.WatchdogService;
import org.synanton.equalix.fairness.FairnessStatistics;

/**
 * EQX-7 definition of done, against PostgreSQL with the real priority calculator and dispatcher in hierarchical
 * mode (layers organization → department).
 */
@Slf4j
@AutoConfigureObservability
@TestPropertySource(properties = {
    "app.queue.fairness-mode=hierarchical",
    "app.queue.max-per-client-quota=0",
    "app.queue.max-tasks-in-process=" + HierarchicalFairnessIntegrationTest.DISPATCH_BATCH_SIZE,
    "app.queue.worker-poll-size=500",
    "app.queue.max-queued-time-ms=3153600000000"
})
class HierarchicalFairnessIntegrationTest extends BaseIntegrationTest {

    static final int DISPATCH_BATCH_SIZE = 20;
    private static final int BACKLOG_PER_LEAF = 2 * DISPATCH_BATCH_SIZE;
    private static final byte[] PAYLOAD = "hierarchy".getBytes();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskIngestionPort taskIngestion;

    @Autowired
    private TaskCompletionPort taskCompletion;

    @Autowired
    private PriorityCalculatorService priorityCalculatorService;

    @Autowired
    private DispatcherService dispatcherService;

    @Autowired
    private WatchdogService watchdogService;

    private final List<UUID> sentTaskIds = new ArrayList<>();
    private final Map<UUID, String> keyByTaskId = new HashMap<>();
    private String runId;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID().toString().substring(0, 8);
        doAnswer(invocation -> sentTaskIds.add(invocation.getArgument(0)))
            .when(remoteExecutor).send(any(), any(), any());
    }

    @Test
    void shouldNotLetHotDepartmentStarveItsSibling() {
        String hot = "acme-" + runId + "/hot";
        String cold = "acme-" + runId + "/cold";
        Map<String, Integer> backlog = new LinkedHashMap<>();
        backlog.put(hot, 10 * BACKLOG_PER_LEAF);
        backlog.put(cold, BACKLOG_PER_LEAF);

        List<String> dispatches = runBacklogged(backlog, 2_000);

        FairnessStatistics statistics = new FairnessStatistics(Map.of(hot, BigDecimal.ONE, cold, BigDecimal.ONE));
        log.info("EQX-7 siblings: shares={} sliding eps_max(W=100)={}",
            statistics.observedShares(dispatches, dispatches.size()), statistics.slidingMaxError(dispatches, 100));
        assertThat(statistics.observedShares(dispatches, dispatches.size()).get(cold)).isCloseTo(0.5, within(0.001));
        assertThat(statistics.slidingMaxError(dispatches, 100)).isLessThanOrEqualTo(0.02 + 1e-9);
    }

    @Test
    void shouldServeNewSiblingWithinOneTickDespiteDeepBacklog() {
        String hot = "acme-" + runId + "/hot";
        String cold = "acme-" + runId + "/cold";
        for (int index = 0; index < 500; index++) {
            ingest(hot);
        }
        priorityCalculatorService.run();
        dispatcherService.dispatch();
        completeAllInFlight();
        sentTaskIds.clear();

        UUID coldTask = ingest(cold);
        priorityCalculatorService.run();
        dispatcherService.dispatch();

        assertThat(sentTaskIds).as("first dispatch tick after the sibling arrives").contains(coldTask);
    }

    @Test
    void shouldBoundOrganizationByItsOwnWeightAgainstSingleLeafTenant() {
        Map<String, Integer> backlog = new LinkedHashMap<>();
        Map<String, BigDecimal> expectedWeights = new LinkedHashMap<>();
        for (int department = 0; department < 10; department++) {
            String key = "big-" + runId + "/dept" + department;
            backlog.put(key, BACKLOG_PER_LEAF);
            expectedWeights.put(key, BigDecimal.ONE);
        }
        String small = "small-" + runId;
        backlog.put(small, BACKLOG_PER_LEAF);
        expectedWeights.put(small, BigDecimal.TEN);

        List<String> dispatches = runBacklogged(backlog, 2_000);

        // Root: big and small each 50%; inside big, 10 departments at 5% each.
        FairnessStatistics statistics = new FairnessStatistics(expectedWeights);
        Map<String, Double> shares = statistics.observedShares(dispatches, dispatches.size());
        log.info("EQX-7 organizations: small={} prefix eps_max={} sliding eps_max(W=100)={}", shares.get(small),
            statistics.prefixMaxError(dispatches, dispatches.size()), statistics.slidingMaxError(dispatches, 100));
        assertThat(shares.get(small)).isCloseTo(0.5, within(0.001));
        assertThat(statistics.prefixMaxError(dispatches, dispatches.size())).isLessThanOrEqualTo(0.001);
    }

    @Test
    void shouldPublishHierarchyDispatchCountersAndLayeredDrift() throws Exception {
        String sales = "acme-" + runId + "/sales";
        String organization = "acme-" + runId + "/";
        for (int index = 0; index < 3; index++) {
            ingest(sales);
        }
        priorityCalculatorService.run();
        dispatcherService.dispatch();

        watchdogService.reconcile();

        assertThat(scrape())
            .contains("equalix_hierarchy_dispatches_total{layer=\"organization\",node=\"" + organization + "\"} 3.0")
            .contains("equalix_cms_estimation_drift_layer_absolute{layer=\"department\"}")
            .contains("equalix_cms_estimation_drift_layer_absolute{layer=\"organization\"}");
        assertThat(hierarchyNodeJpaRepository.findById(organization)).get()
            .extracting(node -> node.getVirtualTime())
            .isEqualTo(3_000.0);
    }

    /** EQX-1 style: keep each leaf at a constant backlog, complete every dispatch, record the dispatch order. */
    private List<String> runBacklogged(Map<String, Integer> backlog, int dispatchCount) {
        backlog.forEach((key, depth) -> {
            for (int index = 0; index < depth; index++) {
                ingest(key);
            }
        });
        List<String> sequence = new ArrayList<>();
        while (sequence.size() < dispatchCount) {
            priorityCalculatorService.run();
            int sentBefore = sentTaskIds.size();
            dispatcherService.dispatch();
            List<UUID> dispatched = List.copyOf(sentTaskIds.subList(sentBefore, sentTaskIds.size()));
            assertThat(dispatched).as("each tick must dispatch while leaves are backlogged").isNotEmpty();
            for (UUID taskId : dispatched) {
                taskCompletion.completeTask(taskId, true, null, null);
                String key = keyByTaskId.get(taskId);
                sequence.add(key);
                ingest(key);
            }
        }
        return sequence.subList(0, dispatchCount);
    }

    private void completeAllInFlight() {
        sentTaskIds.forEach(taskId -> taskCompletion.completeTask(taskId, true, null, null));
    }

    private UUID ingest(String fairnessKey) {
        clock.advance(Duration.ofMillis(1));
        Task task = taskIngestion.createTask(fairnessKey, BigDecimal.ONE, PAYLOAD, false, null, null, false);
        keyByTaskId.put(task.getId(), fairnessKey);
        return task.getId();
    }

    private String scrape() throws Exception {
        return mockMvc.perform(get("/actuator/prometheus").header("X-API-Key", "test-api-key"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    }
}
