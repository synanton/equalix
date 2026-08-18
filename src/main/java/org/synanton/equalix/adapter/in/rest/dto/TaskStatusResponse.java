package org.synanton.equalix.adapter.in.rest.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.experimental.Accessors;
import org.jspecify.annotations.Nullable;
import org.synanton.equalix.domain.model.Task;

@Data
@Accessors(chain = true)
public class TaskStatusResponse {

    private UUID id;
    private String fairnessKey;
    private String status;
    @Nullable private Long priority;
    private Instant createdAt;
    @Nullable private Instant completedAt;
    private int retryCount;
    @Nullable private String lastError;

    public static TaskStatusResponse from(Task task) {
        return new TaskStatusResponse()
            .setId(task.getId())
            .setFairnessKey(task.getFairnessKey())
            .setStatus(task.getStatus().name())
            .setPriority(task.getPriority())
            .setCreatedAt(task.getCreatedAt())
            .setCompletedAt(task.getCompletedAt())
            .setRetryCount(task.getRetryCount())
            .setLastError(task.getLastError());
    }
}
