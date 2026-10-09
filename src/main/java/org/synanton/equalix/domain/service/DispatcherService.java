package org.synanton.equalix.domain.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.synanton.equalix.config.properties.AdaptiveRpsProperties;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.Task;
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
    private final VirtualTimeService virtualTimeService;
    private final AgingService agingService;
    private final FairnessHierarchy fairnessHierarchy;
    private final HierarchicalDispatchPlanner hierarchicalDispatchPlanner;
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

        Instant now = Instant.now(clock);
        HierarchicalDispatchPlanner.Selection hierarchicalSelection = null;
        List<Task> tasks;
        if (fairnessHierarchy.isEnabled()) {
            hierarchicalSelection = hierarchicalDispatchPlanner.select(freeSlots, maxPerClient);
            tasks = hierarchicalSelection.tasks();
        } else if (agingService.isEnabled()) {
            tasks = selectWithAging(freeSlots, maxPerClient, now);
        } else {
            tasks = taskRepository.findAndLockDispatchable(freeSlots, maxPerClient);
        }

        if (tasks.isEmpty()) {
            return;
        }

        // Single bulk UPDATE for the whole tick (the rows are locked by this transaction),
        // then per-task counts/CMS/send. Rowcount mismatch is unexpected under the locks;
        // warn rather than silently dispatching tasks whose transition did not persist.
        int marked = taskRepository.bulkMarkDispatched(tasks.stream().map(Task::getId).toList());
        if (marked != tasks.size()) {
            log.warn("Dispatch transition persisted for {}/{} locked tasks", marked, tasks.size());
        }
        Map<String, Integer> increments = new LinkedHashMap<>();
        for (Task task : tasks) {
            increments.merge(task.getFairnessKey(), 1, Integer::sum);
            cms.add(task.getFairnessKey(), 1);
            remoteExecutor.send(task.getId(), task.getPayload(), null);
        }
        increments.forEach(clientCounts::incrementInFlight);
        boolean aged = hierarchicalSelection == null && agingService.isEnabled();
        virtualTimeService.recordDispatch(tasks, task -> aged ? agingService.credit(task, now) : 0.0);
        if (hierarchicalSelection != null) {
            hierarchicalDispatchPlanner.recordDispatch(hierarchicalSelection);
        }

        log.debug("Dispatched {} tasks; global in-flight was {}", tasks.size(), globalInFlight);
    }

    /**
     * Locks a bounded candidate pool (best base priority plus oldest arrivals) and keeps the best
     * {@code freeSlots} by aged priority. Tasks that are neither near the front nor among the oldest are not
     * considered this tick; their aging credit is smaller than that of every older candidate.
     */
    private List<Task> selectWithAging(int freeSlots, @Nullable Integer maxPerClient, Instant now) {
        int poolSize = agingService.candidatePoolSize(freeSlots);
        Map<UUID, Task> candidates = new LinkedHashMap<>();
        taskRepository.findAndLockDispatchable(poolSize, maxPerClient)
            .forEach(task -> candidates.put(task.getId(), task));
        taskRepository.findAndLockOldestDispatchable(poolSize, maxPerClient)
            .forEach(task -> candidates.putIfAbsent(task.getId(), task));
        return agingService.rank(candidates.values(), freeSlots, now);
    }

    private void promoteStarvedTasks() {
        // Single bulk UPDATE (no per-task load-modify-save); the rowcount feeds the log line.
        int promoted = taskRepository.bulkPromoteStarvedTasks(
            queueProperties.getMaxQueuedTimeMs(),
            queueProperties.getWorkerPollSize());
        if (promoted > 0) {
            log.warn("Promoted {} starved tasks to front of queue", promoted);
        }
    }
}
