package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.config.properties.CmsProperties;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.ClientSequenceState;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientSequenceStateRepositoryPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@ExtendWith(MockitoExtension.class)
class PriorityCalculatorServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private TaskRepositoryPort taskRepository;
    @Mock
    private ClientSequenceStateRepositoryPort sequenceStateRepository;
    @Mock
    private CMSProviderPort cms;
    @Mock
    private AdaptiveRpsController adaptiveRpsController;

    @InjectMocks
    private PriorityCalculatorService service;

    @Test
    void shouldAssignPriorityBasedOnInFlightCountAndPenaltyFactor() {
        QueueProperties props = queueProps();
        service = new PriorityCalculatorService(
            taskRepository, sequenceStateRepository, cms, adaptiveRpsController, props,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        Task task = buildReceivedTask("clientA", false);
        when(taskRepository.findByStatus(TaskStatus.RECEIVED, props.getWorkerPollSize()))
            .thenReturn(List.of(task));
        when(cms.estimateCount("clientA")).thenReturn(3L);
        when(adaptiveRpsController.getPenaltyFactor()).thenReturn(200.0);

        service.run();

        long expectedPriority = FIXED_NOW.toEpochMilli() + (long) (3 * 200.0);
        assertThat(task.getPriority()).isEqualTo(expectedPriority);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.QUEUED);
    }

    @Test
    void shouldGiveHigherWeightTasksBetterPriority() {
        QueueProperties props = queueProps();
        service = new PriorityCalculatorService(
            taskRepository, sequenceStateRepository, cms, adaptiveRpsController, props,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        Task light = buildReceivedTask("clientA", false).setWeight(new BigDecimal("1.0"));
        Task heavy = buildReceivedTask("clientA", false).setWeight(new BigDecimal("2.0"));
        when(taskRepository.findByStatus(TaskStatus.RECEIVED, props.getWorkerPollSize()))
            .thenReturn(List.of(light, heavy));
        when(cms.estimateCount("clientA")).thenReturn(4L);
        when(adaptiveRpsController.getPenaltyFactor()).thenReturn(100.0);

        service.run();

        long lightPriority = FIXED_NOW.toEpochMilli() + (long) (4 * 100.0 / 1.0);
        long heavyPriority = FIXED_NOW.toEpochMilli() + (long) (4 * 100.0 / 2.0);
        assertThat(light.getPriority()).isEqualTo(lightPriority);
        assertThat(heavy.getPriority()).isEqualTo(heavyPriority);
        assertThat(heavy.getPriority()).isLessThan(light.getPriority());
    }

    @Test
    void shouldApplySequenceBoostForSequentialTasks() {
        QueueProperties props = queueProps();
        service = new PriorityCalculatorService(
            taskRepository, sequenceStateRepository, cms, adaptiveRpsController, props,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        Task task = buildReceivedTask("clientA", true);
        task.setSequenceNumber(5L);
        ClientSequenceState state = new ClientSequenceState()
            .setFairnessKey("clientA").setLastCompletedSequence(2L).setBlocked(false);

        when(taskRepository.findByStatus(TaskStatus.RECEIVED, props.getWorkerPollSize()))
            .thenReturn(List.of(task));
        when(cms.estimateCount("clientA")).thenReturn(0L);
        when(adaptiveRpsController.getPenaltyFactor()).thenReturn(100.0);
        when(sequenceStateRepository.findByFairnessKey("clientA")).thenReturn(Optional.of(state));

        service.run();

        long expectedBoost = (5L - 2L) * 100L;
        long expectedPriority = FIXED_NOW.toEpochMilli() + expectedBoost;
        assertThat(task.getPriority()).isEqualTo(expectedPriority);
    }

    @Test
    void shouldApplyBlockedPenaltyForBlockedClients() {
        QueueProperties props = queueProps();
        service = new PriorityCalculatorService(
            taskRepository, sequenceStateRepository, cms, adaptiveRpsController, props,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        Task task = buildReceivedTask("clientA", true);
        task.setSequenceNumber(1L);
        ClientSequenceState state = new ClientSequenceState()
            .setFairnessKey("clientA").setLastCompletedSequence(0L).setBlocked(true);

        when(taskRepository.findByStatus(TaskStatus.RECEIVED, props.getWorkerPollSize()))
            .thenReturn(List.of(task));
        when(cms.estimateCount("clientA")).thenReturn(0L);
        when(adaptiveRpsController.getPenaltyFactor()).thenReturn(100.0);
        when(sequenceStateRepository.findByFairnessKey("clientA")).thenReturn(Optional.of(state));

        service.run();

        assertThat(task.getPriority()).isGreaterThanOrEqualTo(FIXED_NOW.toEpochMilli() + 10_000L);
    }

    @Test
    void shouldPersistEachTaskWithASingleSaveAndNoBatchStatusUpdate() {
        QueueProperties props = queueProps();
        service = new PriorityCalculatorService(
            taskRepository, sequenceStateRepository, cms, adaptiveRpsController, props,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        Task a = buildReceivedTask("clientA", false);
        Task b = buildReceivedTask("clientB", false);
        when(taskRepository.findByStatus(TaskStatus.RECEIVED, props.getWorkerPollSize()))
            .thenReturn(List.of(a, b));
        when(cms.estimateCount(anyString())).thenReturn(0L);
        when(adaptiveRpsController.getPenaltyFactor()).thenReturn(1.0);

        service.run();

        verify(taskRepository, times(1)).save(a);
        verify(taskRepository, times(1)).save(b);
        verify(taskRepository, never()).updateStatusBatch(any(), any());
    }

    @Test
    void shouldDoNothingWhenNoReceivedTasksExist() {
        QueueProperties props = queueProps();
        service = new PriorityCalculatorService(
            taskRepository, sequenceStateRepository, cms, adaptiveRpsController, props,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        when(taskRepository.findByStatus(TaskStatus.RECEIVED, props.getWorkerPollSize()))
            .thenReturn(List.of());

        service.run();

        verifyNoInteractions(cms, adaptiveRpsController);
    }

    private Task buildReceivedTask(String fairnessKey, boolean isSequential) {
        return new Task()
            .setId(UUID.randomUUID())
            .setFairnessKey(fairnessKey)
            .setWeight(new BigDecimal("1.0"))
            .setStatus(TaskStatus.RECEIVED)
            .setPayload(new byte[]{1})
            .setCreatedAt(FIXED_NOW)
            .setUpdatedAt(FIXED_NOW)
            .setSequential(isSequential);
    }

    private QueueProperties queueProps() {
        QueueProperties props = new QueueProperties();
        props.setWorkerPollSize(100);
        props.setCms(new CmsProperties());
        return props;
    }
}
