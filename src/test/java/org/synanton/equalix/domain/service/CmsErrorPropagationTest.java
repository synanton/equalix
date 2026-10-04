package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.synanton.equalix.adapter.out.cms.CountMinSketchAdapter;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.CmsErrorStatistics;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientSequenceStateRepositoryPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

/**
 * EQX-2: validates invariants §17, {@code |P̂_k - P_k| <= p·E / w_k}, through the production
 * {@link PriorityCalculatorService}.
 *
 * <p>The same batch of tasks is prioritised twice with identical virtual-time tags: once with a real, heavily
 * loaded test-size sketch (1024×3, where collisions are frequent) and once with exact counts. Any difference in
 * priority is therefore caused by the CMS error alone.
 */
@Slf4j
class CmsErrorPropagationTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final int KEYS = 3_000;
    private static final int IN_FLIGHT = 5_000;
    private static final int TASKS = 2_000;
    private static final String[] WEIGHTS = {"0.5", "1", "2", "7"};
    /** Priority keeps the integer part of the pressure term, so the two sides can differ by one extra unit. */
    private static final double TRUNCATION = 1.0;

    @ParameterizedTest
    @ValueSource(doubles = {10.0, 100.0, 1000.0})
    void shouldBoundPriorityErrorByPressureTimesCmsErrorOverWeight(double penaltyFactor) {
        SplittableRandom random = new SplittableRandom(17L);
        QueueProperties props = new QueueProperties();
        props.setWorkerPollSize(TASKS);
        props.getCms().setWidth(1_024);
        props.getCms().setDepth(3);
        CountMinSketchAdapter sketch = new CountMinSketchAdapter(props);
        Map<String, Long> exactCounts = new HashMap<>();
        for (int task = 0; task < IN_FLIGHT; task++) {
            String fairnessKey = "tenant-" + random.nextInt(KEYS);
            sketch.add(fairnessKey, 1);
            exactCounts.merge(fairnessKey, 1L, Long::sum);
        }

        List<Task> estimated = new ArrayList<>();
        List<Task> exact = new ArrayList<>();
        for (int index = 0; index < TASKS; index++) {
            Task task = receivedTask("tenant-" + random.nextInt(KEYS), WEIGHTS[random.nextInt(WEIGHTS.length)]);
            estimated.add(task);
            exact.add(copyOf(task));
        }
        prioritise(estimated, sketch, penaltyFactor, props);
        prioritise(exact, new ExactCms(exactCounts), penaltyFactor, props);

        CmsErrorStatistics errors = new CmsErrorStatistics();
        long maxAbsoluteError = 0;
        for (int index = 0; index < TASKS; index++) {
            Task task = estimated.get(index);
            long cmsError = sketch.estimateCount(task.getFairnessKey())
                - exactCounts.getOrDefault(task.getFairnessKey(), 0L);
            errors.record(cmsError);
            maxAbsoluteError = Math.max(maxAbsoluteError, Math.abs(cmsError));
            long priorityError = task.getPriority() - exact.get(index).getPriority();
            double weight = task.effectiveWeight();

            assertThat((double) Math.abs(priorityError))
                .as("task %d key %s e_k=%d w=%s", index, task.getFairnessKey(), cmsError, weight)
                .isLessThanOrEqualTo(penaltyFactor * Math.abs(cmsError) / weight + TRUNCATION);
        }
        long maxError = maxAbsoluteError;
        for (int index = 0; index < TASKS; index++) {
            Task task = estimated.get(index);
            long priorityError = Math.abs(task.getPriority() - exact.get(index).getPriority());
            assertThat((double) priorityError)
                .isLessThanOrEqualTo(penaltyFactor * maxError / task.effectiveWeight() + TRUNCATION);
        }
        assertThat(errors.overestimationFrequency()).as("the sample must contain collisions").isGreaterThan(0.0);
        log.info("EQX-2 §17 p={} E={} cms errors: {}", penaltyFactor, maxError, errors);
    }

    private static void prioritise(List<Task> tasks, CMSProviderPort cms, double penaltyFactor,
        QueueProperties props) {
        TaskRepositoryPort taskRepository = mock(TaskRepositoryPort.class);
        when(taskRepository.findByStatus(TaskStatus.RECEIVED, props.getWorkerPollSize())).thenReturn(tasks);
        AdaptiveRpsController adaptiveRpsController = mock(AdaptiveRpsController.class);
        when(adaptiveRpsController.getPenaltyFactor()).thenReturn(penaltyFactor);
        VirtualTimeService virtualTimeService = mock(VirtualTimeService.class);
        when(virtualTimeService.currentSystemVirtualTime()).thenReturn(0.0);
        // Identical, task-specific tags on both sides so only the pressure term can differ.
        when(virtualTimeService.assignFinishTag(any(Task.class), anyDouble()))
            .thenAnswer(invocation -> (double) Math.floorMod(
                invocation.getArgument(0, Task.class).getId().hashCode(), 1_000_000));

        new PriorityCalculatorService(taskRepository, mock(ClientSequenceStateRepositoryPort.class), cms,
            adaptiveRpsController, virtualTimeService, props, Clock.fixed(NOW, ZoneOffset.UTC)).run();
    }

    private static Task receivedTask(String fairnessKey, String weight) {
        return new Task()
            .setId(UUID.randomUUID())
            .setFairnessKey(fairnessKey)
            .setWeight(new BigDecimal(weight))
            .setStatus(TaskStatus.RECEIVED)
            .setPayload(new byte[]{1})
            .setCreatedAt(NOW)
            .setUpdatedAt(NOW);
    }

    private static Task copyOf(Task task) {
        return new Task()
            .setId(task.getId())
            .setFairnessKey(task.getFairnessKey())
            .setWeight(task.getWeight())
            .setStatus(task.getStatus())
            .setPayload(task.getPayload())
            .setCreatedAt(task.getCreatedAt())
            .setUpdatedAt(task.getUpdatedAt());
    }

    /** CMS port backed by exact counts; the reference side of the comparison. */
    private record ExactCms(Map<String, Long> counts) implements CMSProviderPort {

        @Override
        public void add(String key, long delta) {
            counts.merge(key, delta, Long::sum);
        }

        @Override
        public long estimateCount(String key) {
            return counts.getOrDefault(key, 0L);
        }

        @Override
        public void rebuild(Map<String, Integer> snapshot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long totalInFlight() {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }
    }
}
