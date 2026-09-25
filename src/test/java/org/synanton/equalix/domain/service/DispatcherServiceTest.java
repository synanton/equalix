package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.config.properties.AdaptiveRpsProperties;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.AgingPolicy;
import org.synanton.equalix.domain.model.FairnessMode;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.RemoteExecutorPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@ExtendWith(MockitoExtension.class)
class DispatcherServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final FairnessHierarchy FLAT = FairnessHierarchyTest.hierarchy(FairnessMode.FLAT, Map.of());

    @Mock
    private TaskRepositoryPort taskRepository;
    @Mock
    private CMSProviderPort cms;
    @Mock
    private ClientCountsRepositoryPort clientCounts;
    @Mock
    private RemoteExecutorPort remoteExecutor;
    @Mock
    private AdaptiveRpsController adaptiveRpsController;
    @Mock
    private VirtualTimeService virtualTimeService;
    @Mock
    private HierarchicalDispatchPlanner hierarchicalDispatchPlanner;

    @InjectMocks
    private DispatcherService service;

    @Test
    void shouldDispatchUpToFreeSlots() {
        QueueProperties props = queueProps(10, 0);
        service = new DispatcherService(taskRepository, cms, clientCounts, remoteExecutor, props,
            adaptiveRpsController, adaptiveRpsOff(), virtualTimeService, agingOff(), FLAT, hierarchicalDispatchPlanner,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        when(clientCounts.totalInFlight()).thenReturn(8L);
        when(taskRepository.findStarvedTasks(anyLong(), anyInt())).thenReturn(List.of());
        List<Task> tasks = List.of(buildQueuedTask("clientA"), buildQueuedTask("clientA"));
        when(taskRepository.findAndLockDispatchable(2, null)).thenReturn(tasks);

        service.dispatch();

        verify(remoteExecutor, times(2)).send(any(), any(), isNull());
        verify(cms, times(2)).add(eq("clientA"), eq(1L));
        verify(clientCounts, times(2)).incrementInFlight("clientA");
        verify(virtualTimeService).recordDispatch(eq(tasks), any());
        verify(taskRepository, never()).findAndLockOldestDispatchable(anyInt(), any());
    }

    @Test
    void shouldDoNothingWhenNoFreeSlots() {
        QueueProperties props = queueProps(5, 0);
        service = new DispatcherService(taskRepository, cms, clientCounts, remoteExecutor, props,
            adaptiveRpsController, adaptiveRpsOff(), virtualTimeService, agingOff(), FLAT, hierarchicalDispatchPlanner,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        when(clientCounts.totalInFlight()).thenReturn(5L);
        when(taskRepository.findStarvedTasks(anyLong(), anyInt())).thenReturn(List.of());

        service.dispatch();

        verifyNoInteractions(remoteExecutor, virtualTimeService);
        verify(taskRepository, never()).findAndLockDispatchable(anyInt(), any());
    }

    @Test
    void shouldIncrementCmsAndCountsOnDispatch() {
        QueueProperties props = queueProps(10, 2);
        service = new DispatcherService(taskRepository, cms, clientCounts, remoteExecutor, props,
            adaptiveRpsController, adaptiveRpsOff(), virtualTimeService, agingOff(), FLAT, hierarchicalDispatchPlanner,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        when(clientCounts.totalInFlight()).thenReturn(0L);
        when(taskRepository.findStarvedTasks(anyLong(), anyInt())).thenReturn(List.of());
        Task task = buildQueuedTask("tenantX");
        when(taskRepository.findAndLockDispatchable(10, 2)).thenReturn(List.of(task));

        service.dispatch();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.DISPATCHED);
        assertThat(task.getUpdatedAt()).isEqualTo(FIXED_NOW);
        verify(taskRepository).save(task);
        verify(cms).add("tenantX", 1L);
        verify(clientCounts).incrementInFlight("tenantX");
        verify(remoteExecutor).send(task.getId(), task.getPayload(), null);
    }

    @Test
    void shouldDispatchBestAgedCandidatesFromBothOrderingsWhenAgingIsEnabled() {
        QueueProperties props = queueProps(2, 0);
        service = new DispatcherService(taskRepository, cms, clientCounts, remoteExecutor, props,
            adaptiveRpsController, adaptiveRpsOff(), virtualTimeService,
            AgingServiceTest.service(AgingPolicy.LINEAR, 100.0), FLAT, hierarchicalDispatchPlanner,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        Task front = buildQueuedTask("clientA").setPriority(1_000L);                       // effective 1000
        Task second = buildQueuedTask("clientA").setPriority(1_200L);                      // effective 1200
        Task oldBack = buildQueuedTask("clientB").setPriority(9_000L)
            .setCreatedAt(FIXED_NOW.minusSeconds(85));                                      // effective 500
        when(clientCounts.totalInFlight()).thenReturn(0L);
        when(taskRepository.findStarvedTasks(anyLong(), anyInt())).thenReturn(List.of());
        when(taskRepository.findAndLockDispatchable(200, null)).thenReturn(List.of(front, second));
        when(taskRepository.findAndLockOldestDispatchable(200, null)).thenReturn(List.of(oldBack, front));

        service.dispatch();

        InOrder sendOrder = inOrder(remoteExecutor);
        sendOrder.verify(remoteExecutor).send(oldBack.getId(), oldBack.getPayload(), null);
        sendOrder.verify(remoteExecutor).send(front.getId(), front.getPayload(), null);
        verify(remoteExecutor, never()).send(eq(second.getId()), any(), any());
        assertThat(second.getStatus()).isEqualTo(TaskStatus.QUEUED);
    }

    @Test
    void shouldPassAgingCreditToVirtualTimeOnDispatch() {
        QueueProperties props = queueProps(1, 0);
        service = new DispatcherService(taskRepository, cms, clientCounts, remoteExecutor, props,
            adaptiveRpsController, adaptiveRpsOff(), virtualTimeService,
            AgingServiceTest.service(AgingPolicy.LINEAR, 100.0), FLAT, hierarchicalDispatchPlanner,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        Task aged = buildQueuedTask("clientA").setPriority(9_000L).setCreatedAt(FIXED_NOW.minusSeconds(30));
        when(clientCounts.totalInFlight()).thenReturn(0L);
        when(taskRepository.findStarvedTasks(anyLong(), anyInt())).thenReturn(List.of());
        when(taskRepository.findAndLockDispatchable(200, null)).thenReturn(List.of(aged));
        when(taskRepository.findAndLockOldestDispatchable(200, null)).thenReturn(List.of(aged));

        service.dispatch();

        ArgumentCaptor<ToDoubleFunction<Task>> credit = ArgumentCaptor.captor();
        verify(virtualTimeService).recordDispatch(eq(List.of(aged)), credit.capture());
        assertThat(credit.getValue().applyAsDouble(aged)).isEqualTo(3_000.0);
    }

    @Test
    void shouldDispatchHierarchicalSelectionAndRecordIt() {
        QueueProperties props = queueProps(3, 0);
        FairnessHierarchy hierarchical = FairnessHierarchyTest.hierarchy(FairnessMode.HIERARCHICAL, Map.of());
        service = new DispatcherService(taskRepository, cms, clientCounts, remoteExecutor, props,
            adaptiveRpsController, adaptiveRpsOff(), virtualTimeService, agingOff(), hierarchical,
            hierarchicalDispatchPlanner, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        Task first = buildQueuedTask("acme/cold");
        Task second = buildQueuedTask("acme/hot");
        HierarchicalDispatchPlanner.Selection selection =
            new HierarchicalDispatchPlanner.Selection(List.of(first, second), null);
        when(clientCounts.totalInFlight()).thenReturn(0L);
        when(taskRepository.findStarvedTasks(anyLong(), anyInt())).thenReturn(List.of());
        when(hierarchicalDispatchPlanner.select(3, null)).thenReturn(selection);

        service.dispatch();

        InOrder sendOrder = inOrder(remoteExecutor);
        sendOrder.verify(remoteExecutor).send(first.getId(), first.getPayload(), null);
        sendOrder.verify(remoteExecutor).send(second.getId(), second.getPayload(), null);
        verify(hierarchicalDispatchPlanner).recordDispatch(selection);
        verify(taskRepository, never()).findAndLockDispatchable(anyInt(), any());
    }

    private Task buildQueuedTask(String fairnessKey) {
        return new Task()
            .setId(UUID.randomUUID())
            .setFairnessKey(fairnessKey)
            .setWeight(new BigDecimal("1.0"))
            .setStatus(TaskStatus.QUEUED)
            .setPayload(new byte[]{1})
            .setPriority(0L)
            .setCreatedAt(FIXED_NOW)
            .setUpdatedAt(FIXED_NOW);
    }

    private QueueProperties queueProps(int maxInProcess, int maxPerClient) {
        QueueProperties props = new QueueProperties();
        props.setMaxTasksInProcess(maxInProcess);
        props.setMaxPerClientQuota(maxPerClient);
        props.setMaxQueuedTimeMs(60_000);
        props.setWorkerPollSize(50);
        props.setDispatcherInterval(50);
        return props;
    }

    private AgingService agingOff() {
        return AgingServiceTest.service(AgingPolicy.NONE, 0.0);
    }

    private AdaptiveRpsProperties adaptiveRpsOff() {
        AdaptiveRpsProperties props = new AdaptiveRpsProperties();
        props.setEnabled(false);
        return props;
    }
}
