package org.synanton.equalix.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import org.synanton.equalix.domain.TaskNotFoundException;
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
class GetTaskStatusUseCaseTest {

    @Mock
    private TaskRepositoryPort taskRepository;

    @InjectMocks
    private GetTaskStatusUseCase useCase;

    @Test
    void shouldReturnTaskWhenFound() {
        UUID id = UUID.randomUUID();
        Task task = new Task().setId(id).setFairnessKey("k").setStatus(TaskStatus.QUEUED);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        Task result = useCase.getTask(id);

        assertThat(result).isSameAs(task);
    }

    @Test
    void shouldThrowWhenTaskMissing() {
        UUID id = UUID.randomUUID();
        when(taskRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getTask(id))
            .isInstanceOf(TaskNotFoundException.class)
            .hasMessageContaining(id.toString());
    }

    @Test
    void shouldDelegateListingToRepository() {
        Task a = new Task().setId(UUID.randomUUID()).setFairnessKey("k").setStatus(TaskStatus.QUEUED);
        when(taskRepository.findByFairnessKey("k", TaskStatus.QUEUED)).thenReturn(List.of(a));

        List<Task> result = useCase.getTasksByFairnessKey("k", TaskStatus.QUEUED);

        assertThat(result).containsExactly(a);
    }

    @Test
    void shouldPassThroughNullStatusFilter() {
        Task a = new Task().setId(UUID.randomUUID()).setFairnessKey("k");
        when(taskRepository.findByFairnessKey("k", null)).thenReturn(List.of(a));

        List<Task> result = useCase.getTasksByFairnessKey("k", null);

        assertThat(result).containsExactly(a);
    }
}
