package org.synanton.equalix.domain.port.out;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Outgoing port for sending task payloads to the remote executor.
 * Fire-and-forget: completion is reported back via TaskCompletionPort webhook.
 */
public interface RemoteExecutorPort {

    /**
     * Submits a task payload to the remote executor asynchronously.
     *
     * @param taskId the task being dispatched; used by the remote executor to call back on completion
     * @param payload opaque binary task data
     * @param previousResult result from the predecessor sequential task; null for non-sequential tasks
     */
    void send(UUID taskId, byte[] payload, @Nullable byte[] previousResult);
}
