package org.synanton.equalix.domain.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

/**
 * Recovers sequential tasks that are QUEUED but are missing their predecessor result.
 * This covers the race condition where completion and dispatch arrive out of order.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultPassthroughRecoveryService {

    private final TaskRepositoryPort taskRepository;

    @Transactional
    public void recover() {
        List<Task> waitingTasks = taskRepository.findTasksWaitingForPreviousResult();

        for (Task task : waitingTasks) {
            if (task.getDependsOnTaskId() == null) {
                continue;
            }

            taskRepository.findById(task.getDependsOnTaskId()).ifPresent(predecessor -> {
                if (predecessor.getStatus() == TaskStatus.SUCCEEDED) {
                    task.setPreviousResult(predecessor.getResult());
                    taskRepository.save(task);
                    log.debug("Attached result from predecessor {} to task {}", predecessor.getId(), task.getId());
                } else if (predecessor.getStatus() == TaskStatus.FAILED
                    || predecessor.getStatus() == TaskStatus.TIMEOUT) {
                    task.setStatus(TaskStatus.FAILED)
                        .setLastError("Dependency failed: " + predecessor.getLastError());
                    taskRepository.save(task);
                    log.warn("Marked task {} as FAILED due to failed predecessor {}", task.getId(), predecessor.getId());
                }
            });
        }
    }
}
