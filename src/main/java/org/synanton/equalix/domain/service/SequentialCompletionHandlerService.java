package org.synanton.equalix.domain.service;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.synanton.equalix.domain.model.ClientSequenceState;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.ClientSequenceStateRepositoryPort;
import org.synanton.equalix.domain.port.out.PerformanceMonitorPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

/** Handles completion of sequential tasks and immediately triggers dispatch of the next one. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequentialCompletionHandlerService {

    private final TaskRepositoryPort taskRepository;
    private final ClientSequenceStateRepositoryPort sequenceStateRepository;
    private final CMSProviderPort cms;
    private final ClientCountsRepositoryPort clientCounts;
    private final PerformanceMonitorPort performanceMonitor;
    private final SequentialDispatcherService sequentialDispatcher;
    private final Clock clock;

    @Transactional
    public void handle(Task task, boolean success, @Nullable byte[] result, @Nullable String error) {
        if (task.getStatus().isTerminal()) {
            log.debug("Ignoring duplicate completion for terminal task {}", task.getId());
            return;
        }
        if (!task.getStatus().isInFlight()) {
            throw new IllegalArgumentException("Task is not in-flight: " + task.getId());
        }

        Instant now = Instant.now(clock);
        long durationMs = task.getUpdatedAt() != null
            ? now.toEpochMilli() - task.getUpdatedAt().toEpochMilli()
            : 0L;

        TaskStatus finalStatus = success ? TaskStatus.SUCCEEDED : TaskStatus.FAILED;

        task.setStatus(finalStatus)
            .setResult(result)
            .setLastError(error)
            .setCompletedAt(now)
            .setUpdatedAt(now);
        taskRepository.save(task);

        ClientSequenceState state = sequenceStateRepository.findOrCreate(task.getFairnessKey());
        if (success) {
            state.setLastCompletedSequence(
                    task.getSequenceNumber() != null
                        ? task.getSequenceNumber()
                        : state.getLastCompletedSequence() + 1)
                .setCurrentExecutingTaskId(null)
                .setBlocked(false)
                .setBlockedAt(null)
                .setUpdatedAt(now);
        } else {
            state.setBlocked(true)
                .setBlockedAt(now)
                .setUpdatedAt(now);
        }
        sequenceStateRepository.save(state);

        cms.add(task.getFairnessKey(), -1);
        clientCounts.decrementInFlight(task.getFairnessKey());
        performanceMonitor.recordCompletion(task.getFairnessKey(), durationMs, success);

        if (success) {
            sequentialDispatcher.dispatchNextForClient(state);
        }

        log.debug("Sequential task {} completed with status {}; seq={}", task.getId(), finalStatus,
            task.getSequenceNumber());
    }
}
