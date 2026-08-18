package org.synanton.equalix.domain.service;

import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.domain.model.ClientSequenceState;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.ClientSequenceStateRepositoryPort;
import org.synanton.equalix.domain.port.out.RemoteExecutorPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@ExtendWith(MockitoExtension.class)
class SequentialDispatcherServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private TaskRepositoryPort taskRepository;
    @Mock
    private ClientSequenceStateRepositoryPort sequenceStateRepository;
    @Mock
    private CMSProviderPort cms;
    @Mock
    private ClientCountsRepositoryPort clientCounts;
    @Mock
    private RemoteExecutorPort remoteExecutor;

    private SequentialDispatcherService service;

    @BeforeEach
    void setUp() {
        service = new SequentialDispatcherService(
            taskRepository, sequenceStateRepository, cms, clientCounts, remoteExecutor,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldDispatchFirstTaskWhenClientHasNoCurrentTask() {
        ClientSequenceState state = readyState("clientA", 0);
        Task nextTask = buildSequentialTask("clientA", 1L);

        when(sequenceStateRepository.findReadyClients()).thenReturn(List.of(state));
        when(taskRepository.findNextSequentialTask("clientA", 1L, TaskStatus.QUEUED))
            .thenReturn(Optional.of(nextTask));

        service.dispatch();

        verify(remoteExecutor).send(nextTask.getId(), nextTask.getPayload(), null);
        verify(cms).add("clientA", 1L);
        verify(clientCounts).incrementInFlight("clientA");
    }

    @Test
    void shouldAttachPreviousResultWhenRequiresPreviousResultIsTrue() {
        ClientSequenceState state = readyState("clientA", 0);
        UUID predecessorId = UUID.randomUUID();
        Task nextTask = buildSequentialTask("clientA", 1L);
        nextTask.setRequiresPreviousResult(true).setDependsOnTaskId(predecessorId);
        byte[] previousResult = "previousData".getBytes();
        Task predecessorTask = new Task().setId(predecessorId).setResult(previousResult);

        when(sequenceStateRepository.findReadyClients()).thenReturn(List.of(state));
        when(taskRepository.findNextSequentialTask("clientA", 1L, TaskStatus.QUEUED))
            .thenReturn(Optional.of(nextTask));
        when(taskRepository.findById(predecessorId)).thenReturn(Optional.of(predecessorTask));

        service.dispatch();

        verify(remoteExecutor).send(nextTask.getId(), nextTask.getPayload(), previousResult);
    }

    @Test
    void shouldSkipClientWhenNoNextTaskIsQueued() {
        ClientSequenceState state = readyState("clientA", 2);

        when(sequenceStateRepository.findReadyClients()).thenReturn(List.of(state));
        when(taskRepository.findNextSequentialTask("clientA", 3L, TaskStatus.QUEUED))
            .thenReturn(Optional.empty());

        service.dispatch();

        verifyNoInteractions(remoteExecutor, cms, clientCounts);
    }

    private ClientSequenceState readyState(String fairnessKey, long lastCompleted) {
        return new ClientSequenceState()
            .setFairnessKey(fairnessKey)
            .setLastCompletedSequence(lastCompleted)
            .setLastDispatchedSequence(lastCompleted)
            .setBlocked(false)
            .setUpdatedAt(FIXED_NOW);
    }

    private Task buildSequentialTask(String fairnessKey, long sequenceNumber) {
        return new Task()
            .setId(UUID.randomUUID())
            .setFairnessKey(fairnessKey)
            .setWeight(new BigDecimal("1.0"))
            .setStatus(TaskStatus.QUEUED)
            .setPayload(new byte[]{1})
            .setSequential(true)
            .setSequenceNumber(sequenceNumber)
            .setCreatedAt(FIXED_NOW)
            .setUpdatedAt(FIXED_NOW);
    }
}
