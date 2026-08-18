package org.synanton.equalix.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.synanton.equalix.domain.TaskNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;
import org.synanton.equalix.domain.service.CompletionHandlerService;
import org.synanton.equalix.domain.service.SequentialCompletionHandlerService;

@ExtendWith(MockitoExtension.class)
class CompleteTaskUseCaseTest {

    @Mock
    private TaskRepositoryPort taskRepository;
    @Mock
    private CompletionHandlerService completionHandler;
    @Mock
    private SequentialCompletionHandlerService sequentialCompletionHandler;

    @InjectMocks
    private CompleteTaskUseCase useCase;

    @Test
    void shouldRouteNonSequentialTaskToStandardHandler() {
        UUID id = UUID.randomUUID();
        Task task = new Task().setId(id).setSequential(false);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        useCase.completeTask(id, true, new byte[]{1}, null);

        verify(completionHandler).handle(task, true, new byte[]{1}, null);
        verify(sequentialCompletionHandler, never()).handle(task, true, new byte[]{1}, null);
    }

    @Test
    void shouldRouteSequentialTaskToSequentialHandler() {
        UUID id = UUID.randomUUID();
        Task task = new Task().setId(id).setSequential(true);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        useCase.completeTask(id, false, null, "boom");

        verify(sequentialCompletionHandler).handle(task, false, null, "boom");
        verify(completionHandler, never()).handle(task, false, null, "boom");
    }

    @Test
    void shouldThrowWhenTaskMissing() {
        UUID id = UUID.randomUUID();
        when(taskRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.completeTask(id, true, null, null))
            .isInstanceOf(TaskNotFoundException.class)
            .hasMessageContaining(id.toString());
    }
}
