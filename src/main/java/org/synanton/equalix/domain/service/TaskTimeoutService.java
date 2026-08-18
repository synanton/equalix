package org.synanton.equalix.domain.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.ClientSequenceState;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.ClientSequenceStateRepositoryPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

/** Marks in-flight tasks that exceeded the dispatch timeout as TIMEOUT and releases their slots. */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskTimeoutService {

    private final TaskRepositoryPort taskRepository;
    private final CMSProviderPort cms;
    private final ClientCountsRepositoryPort clientCounts;
    private final ClientSequenceStateRepositoryPort sequenceStateRepository;
    private final QueueProperties queueProperties;
    private final Clock clock;

    @Transactional
    public void expireTimedOutTasks() {
        if (queueProperties.getTaskTimeoutMs() <= 0) {
            return;
        }
        List<Task> timedOut = taskRepository.findTimedOutInFlight(
            queueProperties.getTaskTimeoutMs(), queueProperties.getWorkerPollSize());
        Instant now = Instant.now(clock);
        for (Task task : timedOut) {
            expire(task, now);
        }
        if (!timedOut.isEmpty()) {
            log.warn("Expired {} in-flight tasks as TIMEOUT", timedOut.size());
        }
    }

    private void expire(Task task, Instant now) {
        if (!task.getStatus().isInFlight()) {
            return;
        }
        task.setStatus(TaskStatus.TIMEOUT)
            .setLastError("Exceeded task timeout of " + queueProperties.getTaskTimeoutMs() + "ms")
            .setCompletedAt(now)
            .setUpdatedAt(now);
        taskRepository.save(task);
        cms.add(task.getFairnessKey(), -1);
        clientCounts.decrementInFlight(task.getFairnessKey());

        if (task.isSequential()) {
            ClientSequenceState state = sequenceStateRepository.findOrCreate(task.getFairnessKey());
            state.setBlocked(true)
                .setBlockedAt(now)
                .setCurrentExecutingTaskId(task.getId())
                .setUpdatedAt(now);
            sequenceStateRepository.save(state);
        }
    }
}
