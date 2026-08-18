package org.synanton.equalix.domain;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.TaskNotFoundException;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.in.TaskManagementPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@Component
@RequiredArgsConstructor
public class GetTaskStatusUseCase implements TaskManagementPort {

    private final TaskRepositoryPort taskRepository;

    @Override
    public Task getTask(UUID taskId) {
        return taskRepository.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    @Override
    public List<Task> getTasksByFairnessKey(String fairnessKey, @Nullable TaskStatus status) {
        return taskRepository.findByFairnessKey(fairnessKey, status);
    }
}
