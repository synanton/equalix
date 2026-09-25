package org.synanton.equalix.domain.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.ClientSequenceState;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientSequenceStateRepositoryPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

/**
 * Assigns fairness-weighted priority to RECEIVED tasks and moves them to QUEUED.
 *
 * <p>Priority is {@code P = F + p * F̂_k / w}: the persistent weighted virtual finish tag F (see
 * {@link VirtualTimeService}) plus the current in-flight pressure. Lower values are dispatched first.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriorityCalculatorService {

    private static final long SEQUENCE_BOOST_FACTOR = 100L;
    private static final long BLOCKED_PENALTY = 10_000L;

    private final TaskRepositoryPort taskRepository;
    private final ClientSequenceStateRepositoryPort sequenceStateRepository;
    private final CMSProviderPort cms;
    private final AdaptiveRpsController adaptiveRpsController;
    private final VirtualTimeService virtualTimeService;
    private final QueueProperties queueProperties;
    private final Clock clock;

    @Transactional
    public void run() {
        List<Task> receivedTasks = taskRepository.findByStatus(
            TaskStatus.RECEIVED, queueProperties.getWorkerPollSize());

        if (receivedTasks.isEmpty()) {
            return;
        }

        double systemVirtualTime = virtualTimeService.currentSystemVirtualTime();
        double penaltyFactor = adaptiveRpsController.getPenaltyFactor();
        Instant now = Instant.now(clock);

        // Tasks arrive ordered by creation time, so each key's finish tags follow its submission order.
        for (Task task : receivedTasks) {
            double finishTag = virtualTimeService.assignFinishTag(task, systemVirtualTime);
            long priority = calculatePriority(task, finishTag, penaltyFactor);
            task.setPriority(priority)
                .setStatus(TaskStatus.QUEUED)
                .setUpdatedAt(now);
            taskRepository.save(task);
        }
        log.debug("Calculated priorities for {} tasks", receivedTasks.size());
    }

    private long calculatePriority(Task task, double finishTag, double penaltyFactor) {
        long inFlight = cms.estimateCount(task.getFairnessKey());
        long basePriority = Math.round(finishTag) + (long) (inFlight * penaltyFactor / task.effectiveWeight());

        if (!task.isSequential()) {
            return basePriority;
        }

        Optional<ClientSequenceState> stateOpt =
            sequenceStateRepository.findByFairnessKey(task.getFairnessKey());

        if (stateOpt.isEmpty()) {
            return basePriority;
        }

        ClientSequenceState state = stateOpt.get();
        long seqBoost = task.getSequenceNumber() != null
            ? (task.getSequenceNumber() - state.getLastCompletedSequence()) * SEQUENCE_BOOST_FACTOR
            : 0L;
        long blockedPenalty = state.isBlocked() ? BLOCKED_PENALTY : 0L;

        return basePriority + seqBoost + blockedPenalty;
    }
}
