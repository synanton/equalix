package org.synanton.equalix.domain.port.in;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.synanton.equalix.domain.model.Task;

/** Incoming port for submitting new tasks into the queue. */
public interface TaskIngestionPort {

    /**
     * Creates a new task with status RECEIVED. Priority is assigned later by the Priority Calculator.
     *
     * @param fairnessKey client/tenant identifier used for fairness scheduling
     * @param weight fairness weight; higher weight means proportionally more processing share
     * @param payload opaque binary payload forwarded to the remote executor
     * @param isSequential whether this task must execute after the previous one for the same key
     * @param sequenceNumber position in the client's sequential chain; required when isSequential is true
     * @param dependsOnTaskId ID of the predecessor task whose result is needed
     * @param requiresPreviousResult whether the previous task's result must be attached before dispatch
     * @return the created task with a generated ID
     */
    Task createTask(
        String fairnessKey,
        BigDecimal weight,
        byte[] payload,
        boolean isSequential,
        @Nullable Long sequenceNumber,
        @Nullable UUID dependsOnTaskId,
        boolean requiresPreviousResult
    );
}
