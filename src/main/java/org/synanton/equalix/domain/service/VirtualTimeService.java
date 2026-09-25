package org.synanton.equalix.domain.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.port.out.VirtualTimeRepositoryPort;

/**
 * Maintains persistent weighted virtual time (T_k) using self-clocked fair queueing.
 *
 * <p>When a task is queued it receives a finish tag {@code F = max(virtualFinish_k, V) + quantum * cost / w}.
 * Tags of one key are consecutive, so a backlogged key with weight {@code w} receives a {@code w / sum(w)} share
 * of dispatches when the queue is ordered by tag. When a task is dispatched, T_k and the system virtual time V
 * advance to its tag. A key that was idle restarts from V instead of its stale T_k, so idle periods do not
 * bank credit that could later be spent in a burst.
 */
@Service
@RequiredArgsConstructor
public class VirtualTimeService {

    /** Scheduling cost s_x of a task; all tasks currently have equal cost. */
    private static final double UNIT_TASK_COST = 1.0;

    private final VirtualTimeRepositoryPort virtualTimeRepository;
    private final QueueProperties queueProperties;

    /** Returns the system virtual time V, used as the start-tag floor for a batch of newly queued tasks. */
    public double currentSystemVirtualTime() {
        return virtualTimeRepository.findSystemVirtualTime();
    }

    /**
     * Reserves the next finish tag for the task's fairness key and stores it on the task.
     *
     * @param systemVirtualTime start-tag floor, normally {@link #currentSystemVirtualTime()}
     * @return the assigned finish tag
     */
    public double assignFinishTag(Task task, double systemVirtualTime) {
        double increment = queueProperties.getVirtualTime().getQuantum() * UNIT_TASK_COST / task.effectiveWeight();
        double finishTag = virtualTimeRepository.reserveFinishTag(task.getFairnessKey(), systemVirtualTime, increment);
        task.setVirtualFinish(finishTag);
        return finishTag;
    }

    /**
     * Advances T_k for each dispatched key and the system virtual time V to the highest dispatched tag.
     * Tasks without a finish tag (queued before virtual time was introduced) are ignored.
     */
    public void recordDispatch(Collection<Task> dispatchedTasks) {
        Map<String, Double> highestTagPerKey = new HashMap<>();
        for (Task task : dispatchedTasks) {
            Double finishTag = task.getVirtualFinish();
            if (finishTag != null) {
                highestTagPerKey.merge(task.getFairnessKey(), finishTag, Math::max);
            }
        }
        if (highestTagPerKey.isEmpty()) {
            return;
        }

        highestTagPerKey.forEach(virtualTimeRepository::advanceClientVirtualTime);
        double highestTag = highestTagPerKey.values().stream()
            .mapToDouble(Double::doubleValue)
            .max()
            .orElseThrow();
        virtualTimeRepository.advanceSystemVirtualTime(highestTag);
    }
}
