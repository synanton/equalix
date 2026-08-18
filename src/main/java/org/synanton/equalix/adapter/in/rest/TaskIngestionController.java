package org.synanton.equalix.adapter.in.rest;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.equalix.adapter.in.rest.dto.CreateTaskRequest;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.port.in.TaskIngestionPort;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskIngestionController {

    private final TaskIngestionPort ingestionPort;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UUID createTask(@Valid @RequestBody CreateTaskRequest request) {
        Task task = ingestionPort.createTask(
            request.getFairnessKey(),
            request.getWeight(),
            request.getPayload(),
            request.isSequential(),
            request.getSequenceNumber(),
            request.getDependsOnTaskId(),
            request.isRequiresPreviousResult()
        );
        return task.getId();
    }
}
