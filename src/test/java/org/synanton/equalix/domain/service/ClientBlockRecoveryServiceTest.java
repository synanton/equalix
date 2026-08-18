package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.config.properties.SequentialProperties;
import org.synanton.equalix.domain.model.ClientSequenceState;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.ClientSequenceStateRepositoryPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@ExtendWith(MockitoExtension.class)
class ClientBlockRecoveryServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Mock
    private ClientSequenceStateRepositoryPort sequenceStateRepository;
    @Mock
    private TaskRepositoryPort taskRepository;
    @Mock
    private CMSProviderPort cms;
    @Mock
    private ClientCountsRepositoryPort clientCounts;

    private ClientBlockRecoveryService service;

    @BeforeEach
    void setUp() {
        SequentialProperties props = new SequentialProperties();
        props.setClientBlockTimeoutMs(60_000);
        service = new ClientBlockRecoveryService(
            sequenceStateRepository, taskRepository, cms, clientCounts, props,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldUnblockClientWhenTimeoutExceeded() {
        UUID executingTaskId = UUID.randomUUID();
        Instant blockedAt = FIXED_NOW.minusMillis(120_000);
        ClientSequenceState blocked = new ClientSequenceState()
            .setFairnessKey("clientA")
            .setBlocked(true)
            .setBlockedAt(blockedAt)
            .setCurrentExecutingTaskId(executingTaskId)
            .setLastCompletedSequence(3L)
            .setUpdatedAt(blockedAt);

        Task executingTask = new Task().setId(executingTaskId).setFairnessKey("clientA")
            .setStatus(TaskStatus.DISPATCHED)
            .setUpdatedAt(blockedAt);

        when(sequenceStateRepository.findBlockedClients()).thenReturn(List.of(blocked));
        when(taskRepository.findById(executingTaskId)).thenReturn(Optional.of(executingTask));

        service.recover();

        ArgumentCaptor<ClientSequenceState> savedState = ArgumentCaptor.forClass(ClientSequenceState.class);
        verify(sequenceStateRepository).save(savedState.capture());

        ClientSequenceState result = savedState.getValue();
        assertThat(result.isBlocked()).isFalse();
        assertThat(result.getCurrentExecutingTaskId()).isNull();
        assertThat(result.getLastCompletedSequence()).isEqualTo(4L);
        verify(taskRepository).save(argThat(task -> task.getStatus() == TaskStatus.FAILED));
        verify(cms).add("clientA", -1L);
        verify(clientCounts).decrementInFlight("clientA");
    }

    @Test
    void shouldNotUnblockClientWhenTimeoutNotYetExceeded() {
        Instant recentlyBlocked = FIXED_NOW.minusMillis(1_000);
        ClientSequenceState blocked = new ClientSequenceState()
            .setFairnessKey("clientA")
            .setBlocked(true)
            .setBlockedAt(recentlyBlocked)
            .setUpdatedAt(recentlyBlocked);

        when(sequenceStateRepository.findBlockedClients()).thenReturn(List.of(blocked));

        service.recover();

        verify(sequenceStateRepository, never()).save(any());
    }
}
