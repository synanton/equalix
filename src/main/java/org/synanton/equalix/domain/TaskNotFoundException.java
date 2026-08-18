package org.synanton.equalix.domain;

import java.util.UUID;

/** Thrown when a task id does not exist. */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(UUID taskId) {
        super("Task not found: " + taskId);
    }
}
