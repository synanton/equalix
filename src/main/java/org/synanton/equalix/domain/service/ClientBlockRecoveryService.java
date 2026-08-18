package org.synanton.equalix.domain.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.synanton.equalix.config.properties.SequentialProperties;
import org.synanton.equalix.domain.model.ClientSequenceState;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.ClientSequenceStateRepositoryPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

/** Recovers fairness keys blocked by a failed sequential task after a configurable timeout. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientBlockRecoveryService {

    private final ClientSequenceStateRepositoryPort sequenceStateRepository;
    private final TaskRepositoryPort taskRepository;
    private final CMSProviderPort cms;
    private final ClientCountsRepositoryPort clientCounts;
    private final SequentialProperties sequentialProperties;
    private final Clock clock;

    @Transactional
    public void recover() {
        List<ClientSequenceState> blockedClients = sequenceStateRepository.findBlockedClients();

        for (ClientSequenceState state : blockedClients) {
            if (isTimeoutExceeded(state)) {
                forceUnblock(state);
            }
        }
    }

    private boolean isTimeoutExceeded(ClientSequenceState state) {
        if (state.getBlockedAt() == null) {
            return false;
        }
        long elapsedMs = Instant.now(clock).toEpochMilli() - state.getBlockedAt().toEpochMilli();
        return elapsedMs > sequentialProperties.getClientBlockTimeoutMs();
    }

    private void forceUnblock(ClientSequenceState state) {
        log.warn("Force-unblocking client {} after timeout; failing current task {}",
            state.getFairnessKey(), state.getCurrentExecutingTaskId());

        Instant now = Instant.now(clock);
        if (state.getCurrentExecutingTaskId() != null) {
            taskRepository.findById(state.getCurrentExecutingTaskId()).ifPresent(task -> {
                failInFlightTask(task, now);
            });
        }

        long lastCompleted = state.getLastCompletedSequence() + 1;
        state.setBlocked(false)
            .setCurrentExecutingTaskId(null)
            .setLastCompletedSequence(lastCompleted)
            .setBlockedAt(null)
            .setUpdatedAt(now);
        sequenceStateRepository.save(state);
    }

    private void failInFlightTask(Task task, Instant now) {
        if (!task.getStatus().isInFlight()) {
            return;
        }
        task.setStatus(TaskStatus.FAILED)
            .setLastError("Force-unblocked by ClientBlockRecoveryService after timeout")
            .setCompletedAt(now)
            .setUpdatedAt(now);
        taskRepository.save(task);
        cms.add(task.getFairnessKey(), -1);
        clientCounts.decrementInFlight(task.getFairnessKey());
    }
}
