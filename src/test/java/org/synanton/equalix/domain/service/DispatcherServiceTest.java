package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.config.properties.AdaptiveRpsProperties;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.RemoteExecutorPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@ExtendWith(MockitoExtension.class)
class DispatcherServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

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

    @InjectMocks
    private DispatcherService service;

    @Test
    void shouldDispatchUpToFreeSlots() {
        QueueProperties props = queueProps(10, 0);
        service = new DispatcherService(taskRepository, cms, clientCounts, remoteExecutor, props,
            adaptiveRpsController, adaptiveRpsOff(), Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        when(clientCounts.totalInFlight()).thenReturn(8L);
        when(taskRepository.findStarvedTasks(anyLong(), anyInt())).thenReturn(List.of());
        List<Task> tasks = List.of(buildQueuedTask("clientA"), buildQueuedTask("clientA"));
        when(taskRepository.findAndLockDispatchable(2, null)).thenReturn(tasks);

        service.dispatch();

        verify(remoteExecutor, times(2)).send(any(), any(), isNull());
        verify(cms, times(2)).add(eq("clientA"), eq(1L));
        verify(clientCounts, times(2)).incrementInFlight("clientA");
    }

    @Test
    void shouldDoNothingWhenNoFreeSlots() {
        QueueProperties props = queueProps(5, 0);
        service = new DispatcherService(taskRepository, cms, clientCounts, remoteExecutor, props,
            adaptiveRpsController, adaptiveRpsOff(), Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        when(clientCounts.totalInFlight()).thenReturn(5L);
        when(taskRepository.findStarvedTasks(anyLong(), anyInt())).thenReturn(List.of());

        service.dispatch();

        verifyNoInteractions(remoteExecutor);
        verify(taskRepository, never()).findAndLockDispatchable(anyInt(), any());
    }

    @Test
    void shouldIncrementCmsAndCountsOnDispatch() {
        QueueProperties props = queueProps(10, 2);
        service = new DispatcherService(taskRepository, cms, clientCounts, remoteExecutor, props,
            adaptiveRpsController, adaptiveRpsOff(), Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

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

    private AdaptiveRpsProperties adaptiveRpsOff() {
        AdaptiveRpsProperties props = new AdaptiveRpsProperties();
        props.setEnabled(false);
        return props;
    }
}
