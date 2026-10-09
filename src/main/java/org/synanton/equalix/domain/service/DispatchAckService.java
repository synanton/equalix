package org.synanton.equalix.domain.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

/** Records that the remote executor accepted a dispatched task. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchAckService {

    private final TaskRepositoryPort taskRepository;

    @Transactional
    public void markCommitted(UUID taskId) {
        // Single guarded UPDATE (no load-modify-save round-trip). A zero rowcount means the
        // task already moved on (completed, timed out, never dispatched) — the async ack
        // must never overwrite progress, so it is silently ignored, as before.
        if (taskRepository.markCommitted(taskId)) {
            log.debug("Task {} marked COMMITTED", taskId);
        }
    }
}
