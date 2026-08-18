package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
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

        service.expireTimedOutTasks();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.TIMEOUT);
        verify(cms).add("k", -1L);
        verify(clientCounts).decrementInFlight("k");
        verify(taskRepository).save(task);
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
        when(sequenceStateRepository.findOrCreate("k")).thenReturn(state);

        service.expireTimedOutTasks();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.TIMEOUT);
        assertThat(state.isBlocked()).isTrue();
        assertThat(state.getCurrentExecutingTaskId()).isEqualTo(taskId);
        verify(sequenceStateRepository).save(state);
    }
}
