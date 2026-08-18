package org.synanton.equalix.domain.port.in;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Incoming port for remote executors to report task completion.
 * Routes to the standard or sequential completion handler depending on task type.
 */
public interface TaskCompletionPort {

    /**
     * Records the outcome of a dispatched task.
     *
     * @param taskId ID of the task being completed
     * @param success whether the remote executor succeeded
     * @param result binary result payload; may be null if not applicable or on failure
     * @param error human-readable error description; set when success is false
     */
    void completeTask(UUID taskId, boolean success, @Nullable byte[] result, @Nullable String error);
}
