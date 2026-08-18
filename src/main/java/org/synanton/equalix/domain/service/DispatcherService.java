package org.synanton.equalix.domain.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.synanton.equalix.config.properties.AdaptiveRpsProperties;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.RemoteExecutorPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

/** Selects QUEUED tasks and moves them to DISPATCHED, enforcing global concurrency and optional per-client quota. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatcherService {

    private final TaskRepositoryPort taskRepository;
    private final CMSProviderPort cms;
    private final ClientCountsRepositoryPort clientCounts;
    private final RemoteExecutorPort remoteExecutor;
    private final QueueProperties queueProperties;
    private final AdaptiveRpsController adaptiveRpsController;
    private final AdaptiveRpsProperties adaptiveRpsProperties;
    private final Clock clock;

    @Transactional
    public void dispatch() {
        promoteStarvedTasks();

        long globalInFlight = clientCounts.totalInFlight();
        int freeSlots = (int) Math.max(0, queueProperties.getMaxTasksInProcess() - globalInFlight);
        if (adaptiveRpsProperties.isEnabled()) {
            double intervalSeconds = queueProperties.getDispatcherInterval() / 1000.0;
            int rpsBudget = Math.max(1, (int) Math.ceil(adaptiveRpsController.getCurrentRps() * intervalSeconds));
            freeSlots = Math.min(freeSlots, rpsBudget);
        }

        if (freeSlots == 0) {
            return;
        }

        Integer maxPerClient = queueProperties.getMaxPerClientQuota() > 0
            ? queueProperties.getMaxPerClientQuota()
            : null;

        List<Task> tasks = taskRepository.findAndLockDispatchable(freeSlots, maxPerClient);

        if (tasks.isEmpty()) {
            return;
        }

        Instant now = Instant.now(clock);
        for (Task task : tasks) {
            task.setStatus(TaskStatus.DISPATCHED).setUpdatedAt(now);
            taskRepository.save(task);
            cms.add(task.getFairnessKey(), 1);
            clientCounts.incrementInFlight(task.getFairnessKey());
            remoteExecutor.send(task.getId(), task.getPayload(), null);
        }

        log.debug("Dispatched {} tasks; global in-flight was {}", tasks.size(), globalInFlight);
    }

    private void promoteStarvedTasks() {
        List<Task> starved = taskRepository.findStarvedTasks(
            queueProperties.getMaxQueuedTimeMs(),
            queueProperties.getWorkerPollSize());

        if (starved.isEmpty()) {
            return;
        }

        Instant now = Instant.now(clock);
        for (Task task : starved) {
            // Boost priority to zero to force this task to the front regardless of quota
            task.setPriority(0L).setUpdatedAt(now);
            taskRepository.save(task);
        }
        log.warn("Promoted {} starved tasks to front of queue", starved.size());
    }
}
