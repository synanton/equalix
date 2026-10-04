package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.InOrder;
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
    private static final double SYSTEM_VIRTUAL_TIME = 40_000.0;
    private static final double FINISH_TAG = 42_000.0;

    @Mock
    private TaskRepositoryPort taskRepository;
    @Mock
    private ClientSequenceStateRepositoryPort sequenceStateRepository;
    @Mock
    private CMSProviderPort cms;
    @Mock
    private AdaptiveRpsController adaptiveRpsController;
    @Mock
    private VirtualTimeService virtualTimeService;

    @InjectMocks
    private PriorityCalculatorService service;

    @Test
    void shouldAssignPriorityBasedOnInFlightCountAndPenaltyFactor() {
        QueueProperties props = queueProps();
        service = new PriorityCalculatorService(
            taskRepository, sequenceStateRepository, cms, adaptiveRpsController, virtualTimeService, props,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        Task task = buildReceivedTask("clientA", false);
        when(taskRepository.findByStatus(TaskStatus.RECEIVED, props.getWorkerPollSize()))
            .thenReturn(List.of(task));
        when(cms.estimateCount("clientA")).thenReturn(3L);
        when(adaptiveRpsController.getPenaltyFactor()).thenReturn(200.0);
        stubVirtualTime();

        service.run();

        long expectedPriority = (long) FINISH_TAG + (long) (3 * 200.0);
        assertThat(task.getPriority()).isEqualTo(expectedPriority);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.QUEUED);
    }

    @Test
    void shouldGiveHigherWeightTasksBetterPriority() {
        QueueProperties props = queueProps();
        service = new PriorityCalculatorService(
            taskRepository, sequenceStateRepository, cms, adaptiveRpsController, virtualTimeService, props,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        Task light = buildReceivedTask("clientA", false).setWeight(new BigDecimal("1.0"));
        Task heavy = buildReceivedTask("clientA", false).setWeight(new BigDecimal("2.0"));
        when(taskRepository.findByStatus(TaskStatus.RECEIVED, props.getWorkerPollSize()))
            .thenReturn(List.of(light, heavy));
        when(cms.estimateCount("clientA")).thenReturn(4L);
        when(adaptiveRpsController.getPenaltyFactor()).thenReturn(100.0);
        stubVirtualTime();

        service.run();

        long lightPriority = (long) FINISH_TAG + (long) (4 * 100.0 / 1.0);
        long heavyPriority = (long) FINISH_TAG + (long) (4 * 100.0 / 2.0);
        assertThat(light.getPriority()).isEqualTo(lightPriority);
        assertThat(heavy.getPriority()).isEqualTo(heavyPriority);
        assertThat(heavy.getPriority()).isLessThan(light.getPriority());
    }

    @Test
    void shouldApplySequenceBoostForSequentialTasks() {
        QueueProperties props = queueProps();
        service = new PriorityCalculatorService(
            taskRepository, sequenceStateRepository, cms, adaptiveRpsController, virtualTimeService, props,
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
        stubVirtualTime();

        service.run();

        long expectedBoost = (5L - 2L) * 100L;
        long expectedPriority = (long) FINISH_TAG + expectedBoost;
        assertThat(task.getPriority()).isEqualTo(expectedPriority);
    }

    @Test
    void shouldApplyBlockedPenaltyForBlockedClients() {
        QueueProperties props = queueProps();
        service = new PriorityCalculatorService(
            taskRepository, sequenceStateRepository, cms, adaptiveRpsController, virtualTimeService, props,
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
        stubVirtualTime();

        service.run();

        assertThat(task.getPriority()).isGreaterThanOrEqualTo((long) FINISH_TAG + 10_000L);
    }

    @Test
    void shouldPersistEachTaskWithASingleSaveAndNoBatchStatusUpdate() {
        QueueProperties props = queueProps();
        service = new PriorityCalculatorService(
            taskRepository, sequenceStateRepository, cms, adaptiveRpsController, virtualTimeService, props,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        Task a = buildReceivedTask("clientA", false);
        Task b = buildReceivedTask("clientB", false);
        when(taskRepository.findByStatus(TaskStatus.RECEIVED, props.getWorkerPollSize()))
            .thenReturn(List.of(a, b));
        when(cms.estimateCount(anyString())).thenReturn(0L);
        when(adaptiveRpsController.getPenaltyFactor()).thenReturn(1.0);
        stubVirtualTime();

        service.run();

        verify(taskRepository, times(1)).save(a);
        verify(taskRepository, times(1)).save(b);
        verify(taskRepository, never()).updateStatusBatch(any(), any());
    }

    @Test
    void shouldDoNothingWhenNoReceivedTasksExist() {
        QueueProperties props = queueProps();
        service = new PriorityCalculatorService(
            taskRepository, sequenceStateRepository, cms, adaptiveRpsController, virtualTimeService, props,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        when(taskRepository.findByStatus(TaskStatus.RECEIVED, props.getWorkerPollSize()))
            .thenReturn(List.of());

        service.run();

        verifyNoInteractions(cms, adaptiveRpsController, virtualTimeService);
    }

    @Test
    void shouldReserveFinishTagsInArrivalOrderAgainstOneSystemVirtualTimeSnapshot() {
        QueueProperties props = queueProps();
        service = new PriorityCalculatorService(
            taskRepository, sequenceStateRepository, cms, adaptiveRpsController, virtualTimeService, props,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        Task first = buildReceivedTask("clientA", false);
        Task second = buildReceivedTask("clientB", false);
        Task third = buildReceivedTask("clientA", false);
        when(taskRepository.findByStatus(TaskStatus.RECEIVED, props.getWorkerPollSize()))
            .thenReturn(List.of(first, second, third));
        when(cms.estimateCount(anyString())).thenReturn(0L);
        when(adaptiveRpsController.getPenaltyFactor()).thenReturn(1.0);
        when(virtualTimeService.currentSystemVirtualTime()).thenReturn(SYSTEM_VIRTUAL_TIME);
        when(virtualTimeService.assignFinishTag(first, SYSTEM_VIRTUAL_TIME)).thenReturn(41_000.0);
        when(virtualTimeService.assignFinishTag(second, SYSTEM_VIRTUAL_TIME)).thenReturn(41_000.0);
        when(virtualTimeService.assignFinishTag(third, SYSTEM_VIRTUAL_TIME)).thenReturn(42_000.0);

        service.run();

        InOrder inOrder = inOrder(virtualTimeService);
        inOrder.verify(virtualTimeService).currentSystemVirtualTime();
        inOrder.verify(virtualTimeService).assignFinishTag(first, SYSTEM_VIRTUAL_TIME);
        inOrder.verify(virtualTimeService).assignFinishTag(second, SYSTEM_VIRTUAL_TIME);
        inOrder.verify(virtualTimeService).assignFinishTag(third, SYSTEM_VIRTUAL_TIME);
        assertThat(List.of(first.getPriority(), second.getPriority(), third.getPriority()))
            .containsExactly(41_000L, 41_000L, 42_000L);
    }

    @Test
    void shouldRoundFractionalFinishTagIntoPriority() {
        QueueProperties props = queueProps();
        service = new PriorityCalculatorService(
            taskRepository, sequenceStateRepository, cms, adaptiveRpsController, virtualTimeService, props,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        Task task = buildReceivedTask("clientA", false).setWeight(new BigDecimal("7.0"));
        when(taskRepository.findByStatus(TaskStatus.RECEIVED, props.getWorkerPollSize()))
            .thenReturn(List.of(task));
        when(cms.estimateCount("clientA")).thenReturn(0L);
        when(adaptiveRpsController.getPenaltyFactor()).thenReturn(1.0);
        when(virtualTimeService.currentSystemVirtualTime()).thenReturn(0.0);
        when(virtualTimeService.assignFinishTag(task, 0.0)).thenReturn(1000.0 / 7.0);

        service.run();

        assertThat(task.getPriority()).isEqualTo(143L);
    }

    private void stubVirtualTime() {
        when(virtualTimeService.currentSystemVirtualTime()).thenReturn(SYSTEM_VIRTUAL_TIME);
        when(virtualTimeService.assignFinishTag(any(Task.class), eq(SYSTEM_VIRTUAL_TIME))).thenReturn(FINISH_TAG);
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
