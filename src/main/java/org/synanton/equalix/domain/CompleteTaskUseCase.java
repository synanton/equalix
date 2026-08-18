package org.synanton.equalix.domain;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.TaskNotFoundException;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.port.in.TaskCompletionPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;
import org.synanton.equalix.domain.service.CompletionHandlerService;
import org.synanton.equalix.domain.service.SequentialCompletionHandlerService;

@Component
@RequiredArgsConstructor
public class CompleteTaskUseCase implements TaskCompletionPort {

    private final TaskRepositoryPort taskRepository;
    private final CompletionHandlerService completionHandler;
    private final SequentialCompletionHandlerService sequentialCompletionHandler;

    @Override
    public void completeTask(UUID taskId, boolean success, @Nullable byte[] result, @Nullable String error) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));

        if (task.isSequential()) {
            sequentialCompletionHandler.handle(task, success, result, error);
        } else {
            completionHandler.handle(task, success, result, error);
        }
    }
}
