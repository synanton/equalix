package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.model.VirtualTimeState;
import org.synanton.equalix.domain.port.out.VirtualTimeRepositoryPort;

@ExtendWith(MockitoExtension.class)
class VirtualTimeServiceTest {

    private static final double QUANTUM = 1000.0;
    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private VirtualTimeRepositoryPort virtualTimeRepository;
    @Spy
    private QueueProperties queueProperties = queueProps();

    @InjectMocks
    private VirtualTimeService service;

    @Test
    void shouldReturnSystemVirtualTimeFromRepository() {
        when(virtualTimeRepository.findSystemVirtualTime()).thenReturn(1234.5);

        assertThat(service.currentSystemVirtualTime()).isEqualTo(1234.5);
    }

    @Test
    void shouldChargeQuantumDividedByWeightAndStoreTagOnTask() {
        Task task = task("clientA", "2.0");
        when(virtualTimeRepository.reserveFinishTag("clientA", 100.0, 500.0)).thenReturn(600.0);

        double finishTag = service.assignFinishTag(task, 100.0);

        assertThat(finishTag).isEqualTo(600.0);
        assertThat(task.getVirtualFinish()).isEqualTo(600.0);
    }

    @Test
    void shouldTreatNonPositiveWeightAsOne() {
        Task task = task("clientA", "0");
        when(virtualTimeRepository.reserveFinishTag("clientA", 0.0, QUANTUM)).thenReturn(QUANTUM);

        service.assignFinishTag(task, 0.0);

        verify(virtualTimeRepository).reserveFinishTag("clientA", 0.0, QUANTUM);
    }

    @Test
    void shouldAdvanceEachKeyToItsHighestTagAndSystemToOverallHighest() {
        List<Task> dispatched = List.of(
            task("clientA", "1.0").setVirtualFinish(1000.0),
            task("clientA", "1.0").setVirtualFinish(3000.0),
            task("clientB", "1.0").setVirtualFinish(2000.0));

        service.recordDispatch(dispatched);

        verify(virtualTimeRepository).advanceClientVirtualTime("clientA", 3000.0);
        verify(virtualTimeRepository).advanceClientVirtualTime("clientB", 2000.0);
        verify(virtualTimeRepository).advanceSystemVirtualTime(3000.0);
        verifyNoMoreInteractions(virtualTimeRepository);
    }

    @Test
    void shouldChargeKeyFullTagButAdvanceSystemTimeOnlyToAgedPosition() {
        Task promoted = task("lowWeight", "1.0").setVirtualFinish(50_000.0);
        Task regular = task("clientB", "1.0").setVirtualFinish(2_000.0);

        service.recordDispatch(List.of(promoted, regular), task -> task == promoted ? 47_500.0 : 0.0);

        verify(virtualTimeRepository).advanceClientVirtualTime("lowWeight", 50_000.0);
        verify(virtualTimeRepository).advanceClientVirtualTime("clientB", 2_000.0);
        verify(virtualTimeRepository).advanceSystemVirtualTime(2_500.0);
        verifyNoMoreInteractions(virtualTimeRepository);
    }

    @Test
    void shouldIgnoreDispatchedTasksWithoutFinishTag() {
        service.recordDispatch(List.of(task("legacy", "1.0")));

        verifyNoInteractions(virtualTimeRepository);
    }

    @Test
    void shouldConvergeToWeightedSharesForBackloggedKeys() {
        Map<String, String> weights = new LinkedHashMap<>();
        weights.put("tenantA", "1");
        weights.put("tenantB", "2");
        weights.put("tenantC", "7");
        SchedulerSimulation simulation = new SchedulerSimulation(new InMemoryVirtualTimeRepository(FIXED_NOW));

        Map<String, Integer> dispatches = simulation.runBacklogged(weights, 10_000);

        assertThat(share(dispatches, "tenantA")).isCloseTo(0.10, within(0.001));
        assertThat(share(dispatches, "tenantB")).isCloseTo(0.20, within(0.001));
        assertThat(share(dispatches, "tenantC")).isCloseTo(0.70, within(0.001));
    }

    @Test
    void shouldNotLetIdleKeyBankCreditForALaterBurst() {
        SchedulerSimulation simulation = new SchedulerSimulation(new InMemoryVirtualTimeRepository(FIXED_NOW));
        simulation.runBacklogged(Map.of("busy", "1"), 1_000);

        Map<String, String> weights = new LinkedHashMap<>();
        weights.put("busy", "1");
        weights.put("returning", "1");
        Map<String, Integer> dispatches = simulation.runBacklogged(weights, 100);

        // Without the system virtual time floor, "returning" would start at T=0 and win all 100 slots.
        assertThat(dispatches.get("returning")).isBetween(49, 51);
    }

    @Test
    void shouldResumeFromPersistedStateAfterRestart() {
        InMemoryVirtualTimeRepository repository = new InMemoryVirtualTimeRepository(FIXED_NOW);
        VirtualTimeService beforeRestart = new VirtualTimeService(repository, queueProps());
        Task first = task("clientA", "1.0");
        beforeRestart.assignFinishTag(first, beforeRestart.currentSystemVirtualTime());
        beforeRestart.recordDispatch(List.of(first));

        VirtualTimeService afterRestart = new VirtualTimeService(repository, queueProps());
        double secondTag = afterRestart.assignFinishTag(
            task("clientA", "1.0"), afterRestart.currentSystemVirtualTime());

        assertThat(secondTag).isEqualTo(2 * QUANTUM);
        assertThat(repository.findByFairnessKey("clientA")).contains(new VirtualTimeState()
            .setFairnessKey("clientA")
            .setVirtualTime(QUANTUM)
            .setVirtualFinish(2 * QUANTUM)
            .setUpdatedAt(FIXED_NOW));
    }

    private static double share(Map<String, Integer> dispatches, String fairnessKey) {
        int total = dispatches.values().stream().mapToInt(Integer::intValue).sum();
        return (double) dispatches.getOrDefault(fairnessKey, 0) / total;
    }

    private static Task task(String fairnessKey, String weight) {
        return new Task()
            .setId(UUID.randomUUID())
            .setFairnessKey(fairnessKey)
            .setWeight(new BigDecimal(weight))
            .setStatus(TaskStatus.RECEIVED)
            .setPayload(new byte[]{1})
            .setCreatedAt(FIXED_NOW)
            .setUpdatedAt(FIXED_NOW);
    }

    private static QueueProperties queueProps() {
        QueueProperties props = new QueueProperties();
        props.getVirtualTime().setQuantum(QUANTUM);
        return props;
    }

    /**
     * Mirrors the production pipeline: tag newly queued tasks against a V snapshot, dispatch the lowest
     * (tag, arrival) task, then record the dispatch. Each key keeps a constant backlog.
     */
    private static final class SchedulerSimulation {

        private static final int BACKLOG_PER_KEY = 5;

        private final VirtualTimeService virtualTimeService;
        private final PriorityQueue<QueuedTask> queue = new PriorityQueue<>(
            Comparator.comparingDouble(QueuedTask::finishTag).thenComparingLong(QueuedTask::arrival));
        private long arrivalCounter;

        SchedulerSimulation(VirtualTimeRepositoryPort repository) {
            this.virtualTimeService = new VirtualTimeService(repository, queueProps());
        }

        Map<String, Integer> runBacklogged(Map<String, String> weights, int dispatchCount) {
            weights.forEach((fairnessKey, weight) -> {
                boolean alreadyBacklogged = queue.stream()
                    .anyMatch(queued -> queued.task().getFairnessKey().equals(fairnessKey));
                if (!alreadyBacklogged) {
                    enqueue(fairnessKey, weight, BACKLOG_PER_KEY);
                }
            });

            Map<String, Integer> dispatches = new HashMap<>();
            for (int dispatchIndex = 0; dispatchIndex < dispatchCount; dispatchIndex++) {
                QueuedTask next = queue.poll();
                virtualTimeService.recordDispatch(List.of(next.task()));
                dispatches.merge(next.task().getFairnessKey(), 1, Integer::sum);
                enqueue(next.task().getFairnessKey(), weights.get(next.task().getFairnessKey()), 1);
            }
            return dispatches;
        }

        private void enqueue(String fairnessKey, String weight, int count) {
            double systemVirtualTime = virtualTimeService.currentSystemVirtualTime();
            List<QueuedTask> batch = new ArrayList<>();
            for (int taskIndex = 0; taskIndex < count; taskIndex++) {
                Task task = task(fairnessKey, weight);
                double finishTag = virtualTimeService.assignFinishTag(task, systemVirtualTime);
                batch.add(new QueuedTask(task, finishTag, arrivalCounter++));
            }
            queue.addAll(batch);
        }
    }

    private record QueuedTask(Task task, double finishTag, long arrival) {
    }
}
