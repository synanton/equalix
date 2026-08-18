package org.synanton.equalix.adapter.in.rest;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.equalix.adapter.in.rest.dto.TaskStatusResponse;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.in.TaskManagementPort;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskManagementController {

    private final TaskManagementPort managementPort;

    @GetMapping("/{taskId}")
    public TaskStatusResponse getTask(@PathVariable UUID taskId) {
        return TaskStatusResponse.from(managementPort.getTask(taskId));
    }

    @GetMapping
    public List<TaskStatusResponse> getTasksByClient(
        @RequestParam String fairnessKey,
        @Nullable @RequestParam(required = false) TaskStatus status
    ) {
        return managementPort.getTasksByFairnessKey(fairnessKey, status)
            .stream().map(TaskStatusResponse::from).toList();
    }
}
