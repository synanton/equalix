package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.ClientSequenceState;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.ClientSequenceStateRepositoryPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@ExtendWith(MockitoExtension.class)
class TaskTimeoutServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private TaskRepositoryPort taskRepository;
    @Mock
    private CMSProviderPort cms;
    @Mock
    private ClientCountsRepositoryPort clientCounts;
    @Mock
    private ClientSequenceStateRepositoryPort sequenceStateRepository;

    private TaskTimeoutService service;

    @BeforeEach
    void setUp() {
        QueueProperties props = new QueueProperties();
        props.setTaskTimeoutMs(60_000);
        props.setWorkerPollSize(10);
        service = new TaskTimeoutService(
            taskRepository, cms, clientCounts, sequenceStateRepository, props,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldExpireInFlightTaskAndReleaseSlot() {
        Task task = new Task()
            .setId(UUID.randomUUID())
            .setFairnessKey("k")
            .setStatus(TaskStatus.DISPATCHED)
            .setSequential(false);
        when(taskRepository.findTimedOutInFlight(60_000, 10)).thenReturn(List.of(task));
        when(taskRepository.markTimeout(eq(task.getId()), anyLong(), anyString(), any())).thenReturn(true);

        service.expireTimedOutTasks();

        verify(taskRepository).markTimeout(eq(task.getId()), eq(0L), contains("60000"), eq(FIXED_NOW));
        verify(cms).add("k", -1L);
        verify(clientCounts).decrementInFlight("k");
    }

    @Test
    void shouldSkipSlotReleaseWhenTaskMovedConcurrently() {
        Task task = new Task()
            .setId(UUID.randomUUID())
            .setFairnessKey("k")
            .setStatus(TaskStatus.DISPATCHED)
            .setSequential(false);
        when(taskRepository.findTimedOutInFlight(60_000, 10)).thenReturn(List.of(task));
        when(taskRepository.markTimeout(eq(task.getId()), anyLong(), anyString(), any())).thenReturn(false);

        service.expireTimedOutTasks();

        verifyNoInteractions(cms, clientCounts);
    }

    @Test
    void shouldBlockSequentialKeyOnTimeout() {
        UUID taskId = UUID.randomUUID();
        Task task = new Task()
            .setId(taskId)
            .setFairnessKey("k")
            .setStatus(TaskStatus.COMMITTED)
            .setSequential(true);
        ClientSequenceState state = new ClientSequenceState().setFairnessKey("k");
        when(taskRepository.findTimedOutInFlight(60_000, 10)).thenReturn(List.of(task));
        when(taskRepository.markTimeout(eq(taskId), anyLong(), anyString(), any())).thenReturn(true);
        when(sequenceStateRepository.findOrCreate("k")).thenReturn(state);

        service.expireTimedOutTasks();

        assertThat(state.isBlocked()).isTrue();
        assertThat(state.getCurrentExecutingTaskId()).isEqualTo(taskId);
        verify(sequenceStateRepository).save(state);
    }
}
