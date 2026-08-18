package org.synanton.equalix.domain.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.synanton.equalix.domain.model.ClientSequenceState;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.ClientSequenceStateRepositoryPort;
import org.synanton.equalix.domain.port.out.RemoteExecutorPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

/** Dispatches one sequential task at a time per client, maintaining strict ordering. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequentialDispatcherService {

    private final TaskRepositoryPort taskRepository;
    private final ClientSequenceStateRepositoryPort sequenceStateRepository;
    private final CMSProviderPort cms;
    private final ClientCountsRepositoryPort clientCounts;
    private final RemoteExecutorPort remoteExecutor;
    private final Clock clock;

    @Transactional
    public void dispatch() {
        List<ClientSequenceState> readyClients = sequenceStateRepository.findReadyClients();

        for (ClientSequenceState state : readyClients) {
            dispatchNextForClient(state);
        }
    }

    @Transactional
    public void dispatchNextForClient(ClientSequenceState state) {
        long nextSeq = state.getLastCompletedSequence() + 1;
        Optional<Task> nextTaskOpt = taskRepository.findNextSequentialTask(
            state.getFairnessKey(), nextSeq, TaskStatus.QUEUED);

        if (nextTaskOpt.isEmpty()) {
            return;
        }

        Task nextTask = nextTaskOpt.get();

        byte[] previousResult = null;
        if (nextTask.isRequiresPreviousResult() && nextTask.getDependsOnTaskId() != null) {
            previousResult = taskRepository.findById(nextTask.getDependsOnTaskId())
                .map(Task::getResult)
                .orElse(null);
            if (previousResult == null) {
                log.debug("Previous result not yet available for task {}, deferring", nextTask.getId());
                return;
            }
            nextTask.setPreviousResult(previousResult);
        }

        Instant now = Instant.now(clock);
        nextTask.setStatus(TaskStatus.DISPATCHED).setUpdatedAt(now);
        taskRepository.save(nextTask);

        state.setCurrentExecutingTaskId(nextTask.getId())
            .setLastDispatchedSequence(nextTask.getSequenceNumber() != null ? nextTask.getSequenceNumber() : 0L)
            .setUpdatedAt(now);
        sequenceStateRepository.save(state);

        cms.add(state.getFairnessKey(), 1);
        clientCounts.incrementInFlight(state.getFairnessKey());
        remoteExecutor.send(nextTask.getId(), nextTask.getPayload(), previousResult);

        log.debug("Dispatched sequential task {} seq={} for client {}",
            nextTask.getId(), nextTask.getSequenceNumber(), state.getFairnessKey());
    }
}
