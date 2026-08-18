package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.domain.model.ClientSequenceState;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.ClientSequenceStateRepositoryPort;
import org.synanton.equalix.domain.port.out.PerformanceMonitorPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@ExtendWith(MockitoExtension.class)
class SequentialCompletionHandlerServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:01Z");
    private static final Instant DISPATCH_AT = FIXED_NOW.minusMillis(500);

    @Mock
    private TaskRepositoryPort taskRepository;
    @Mock
    private ClientSequenceStateRepositoryPort sequenceStateRepository;
    @Mock
    private CMSProviderPort cms;
    @Mock
    private ClientCountsRepositoryPort clientCounts;
    @Mock
    private PerformanceMonitorPort performanceMonitor;
    @Mock
    private SequentialDispatcherService sequentialDispatcher;

    private SequentialCompletionHandlerService service;

    @BeforeEach
    void setUp() {
        service = new SequentialCompletionHandlerService(
            taskRepository, sequenceStateRepository, cms, clientCounts,
            performanceMonitor, sequentialDispatcher,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldAdvanceSequenceAndTriggerNextDispatchOnSuccess() {
        Task task = buildSequentialTask(3L);
        ClientSequenceState state = new ClientSequenceState()
            .setFairnessKey("k").setLastCompletedSequence(2L)
            .setCurrentExecutingTaskId(task.getId()).setBlocked(false);
        when(sequenceStateRepository.findOrCreate("k")).thenReturn(state);

        service.handle(task, true, new byte[]{7}, null);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(state.getLastCompletedSequence()).isEqualTo(3L);
        assertThat(state.getCurrentExecutingTaskId()).isNull();

        verify(taskRepository).save(task);
        verify(sequenceStateRepository).save(state);
        verify(cms).add("k", -1L);
        verify(clientCounts).decrementInFlight("k");
        verify(performanceMonitor).recordCompletion("k", 500L, true);
        verify(sequentialDispatcher).dispatchNextForClient(state);
    }

    @Test
    void shouldNotDispatchNextOnFailure() {
        Task task = buildSequentialTask(3L);
        ClientSequenceState state = new ClientSequenceState()
            .setFairnessKey("k").setLastCompletedSequence(2L)
            .setCurrentExecutingTaskId(task.getId());
        when(sequenceStateRepository.findOrCreate("k")).thenReturn(state);

        service.handle(task, false, null, "boom");

        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getLastError()).isEqualTo("boom");
        assertThat(state.isBlocked()).isTrue();
        assertThat(state.getBlockedAt()).isEqualTo(FIXED_NOW);
        assertThat(state.getLastCompletedSequence()).isEqualTo(2L);
        verify(sequentialDispatcher, never()).dispatchNextForClient(any());
    }

    @Test
    void shouldIncrementLastCompletedWhenSequenceNumberIsNull() {
        Task task = buildSequentialTask(null);
        ClientSequenceState state = new ClientSequenceState()
            .setFairnessKey("k").setLastCompletedSequence(7L);
        when(sequenceStateRepository.findOrCreate("k")).thenReturn(state);

        service.handle(task, true, null, null);

        ArgumentCaptor<ClientSequenceState> captor = ArgumentCaptor.forClass(ClientSequenceState.class);
        verify(sequenceStateRepository).save(captor.capture());
        assertThat(captor.getValue().getLastCompletedSequence()).isEqualTo(8L);
    }

    private Task buildSequentialTask(Long sequenceNumber) {
        return new Task()
            .setId(UUID.randomUUID())
            .setFairnessKey("k")
            .setStatus(TaskStatus.DISPATCHED)
            .setSequential(true)
            .setSequenceNumber(sequenceNumber)
            .setPayload(new byte[]{1})
            .setCreatedAt(DISPATCH_AT)
            .setUpdatedAt(DISPATCH_AT);
    }
}
