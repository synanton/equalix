package org.synanton.equalix.domain.port.in;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;

/** Incoming port for operational task inspection. */
public interface TaskManagementPort {

    /**
     * Returns the current state of a single task.
     *
     * @param taskId the task to look up
     * @return the task domain object
     * @throws jakarta.persistence.EntityNotFoundException if no task with the given ID exists
     */
    Task getTask(UUID taskId);

    /**
     * Returns all tasks for a given fairness key filtered by status.
     *
     * @param fairnessKey client/tenant identifier
     * @param status status filter; returns all statuses when null
     * @return matching tasks ordered by created_at ascending
     */
    List<Task> getTasksByFairnessKey(String fairnessKey, @Nullable TaskStatus status);
}
