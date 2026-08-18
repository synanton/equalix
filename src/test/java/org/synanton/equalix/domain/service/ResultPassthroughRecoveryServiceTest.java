package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@ExtendWith(MockitoExtension.class)
class ResultPassthroughRecoveryServiceTest {

    @Mock
    private TaskRepositoryPort taskRepository;

    @InjectMocks
    private ResultPassthroughRecoveryService service;

    @Test
    void shouldAttachPredecessorResultWhenSucceeded() {
        UUID predId = UUID.randomUUID();
        byte[] predResult = new byte[]{4, 2};
        Task waiting = new Task()
            .setId(UUID.randomUUID())
            .setDependsOnTaskId(predId)
            .setRequiresPreviousResult(true)
            .setStatus(TaskStatus.QUEUED);
        Task predecessor = new Task()
            .setId(predId)
            .setStatus(TaskStatus.SUCCEEDED)
            .setResult(predResult);

        when(taskRepository.findTasksWaitingForPreviousResult()).thenReturn(List.of(waiting));
        when(taskRepository.findById(predId)).thenReturn(Optional.of(predecessor));

        service.recover();

        assertThat(waiting.getPreviousResult()).isSameAs(predResult);
        assertThat(waiting.getStatus()).isEqualTo(TaskStatus.QUEUED);
        verify(taskRepository).save(waiting);
    }

    @Test
    void shouldMarkWaitingTaskFailedWhenPredecessorFailed() {
        UUID predId = UUID.randomUUID();
        Task waiting = new Task()
            .setId(UUID.randomUUID())
            .setDependsOnTaskId(predId)
            .setRequiresPreviousResult(true)
            .setStatus(TaskStatus.QUEUED);
        Task predecessor = new Task()
            .setId(predId)
            .setStatus(TaskStatus.FAILED)
            .setLastError("upstream crashed");

        when(taskRepository.findTasksWaitingForPreviousResult()).thenReturn(List.of(waiting));
        when(taskRepository.findById(predId)).thenReturn(Optional.of(predecessor));

        service.recover();

        assertThat(waiting.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(waiting.getLastError()).contains("upstream crashed");
        verify(taskRepository).save(waiting);
    }

    @Test
    void shouldMarkWaitingTaskFailedOnTimeoutPredecessor() {
        UUID predId = UUID.randomUUID();
        Task waiting = new Task()
            .setId(UUID.randomUUID())
            .setDependsOnTaskId(predId)
            .setRequiresPreviousResult(true)
            .setStatus(TaskStatus.QUEUED);
        Task predecessor = new Task()
            .setId(predId)
            .setStatus(TaskStatus.TIMEOUT)
            .setLastError("timed out");

        when(taskRepository.findTasksWaitingForPreviousResult()).thenReturn(List.of(waiting));
        when(taskRepository.findById(predId)).thenReturn(Optional.of(predecessor));

        service.recover();

        assertThat(waiting.getStatus()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void shouldSkipWaitingTasksWithoutDependsOnTaskId() {
        Task waiting = new Task()
            .setId(UUID.randomUUID())
            .setDependsOnTaskId(null)
            .setRequiresPreviousResult(true)
            .setStatus(TaskStatus.QUEUED);

        when(taskRepository.findTasksWaitingForPreviousResult()).thenReturn(List.of(waiting));

        service.recover();

        verify(taskRepository, never()).save(any());
    }

    @Test
    void shouldNotMutateWhenPredecessorMissing() {
        UUID predId = UUID.randomUUID();
        Task waiting = new Task()
            .setId(UUID.randomUUID())
            .setDependsOnTaskId(predId)
            .setRequiresPreviousResult(true)
            .setStatus(TaskStatus.QUEUED);

        when(taskRepository.findTasksWaitingForPreviousResult()).thenReturn(List.of(waiting));
        when(taskRepository.findById(predId)).thenReturn(Optional.empty());

        service.recover();

        assertThat(waiting.getPreviousResult()).isNull();
        assertThat(waiting.getStatus()).isEqualTo(TaskStatus.QUEUED);
        verify(taskRepository, never()).save(any());
    }
}
