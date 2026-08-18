package org.synanton.equalix.adapter.in.rest;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.equalix.adapter.in.rest.dto.CompleteTaskRequest;
import org.synanton.equalix.domain.port.in.TaskCompletionPort;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskCompletionController {

    private final TaskCompletionPort completionPort;

    @PostMapping("/{taskId}/complete")
    public void completeTask(
        @PathVariable UUID taskId,
        @Valid @RequestBody CompleteTaskRequest request
    ) {
        completionPort.completeTask(taskId, request.isSuccess(), request.getResult(), request.getError());
    }
}
