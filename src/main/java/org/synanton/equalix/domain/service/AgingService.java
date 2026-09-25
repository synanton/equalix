package org.synanton.equalix.domain.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.synanton.equalix.config.properties.AgingProperties;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.AgingPolicy;
import org.synanton.equalix.domain.model.Task;

/**
 * Applies anti-starvation aging at selection time: {@code P_x(t) = P_base - A(W_x(t))} (invariants §10), where
 * {@code P_base} is the stored priority and {@code W_x} the time since the task was created.
 *
 * <p>Non-linear aging changes the relative order of queued tasks as time passes, so it cannot be folded into the
 * priority stored at queue time and is evaluated when the dispatcher selects tasks.
 */
@Service
@RequiredArgsConstructor
public class AgingService {

    private final QueueProperties queueProperties;

    public boolean isEnabled() {
        return aging().getPolicy() != AgingPolicy.NONE;
    }

    /** Rows to lock from each candidate ordering; never fewer than the slots being filled. */
    public int candidatePoolSize(int freeSlots) {
        return Math.max(freeSlots, aging().getCandidatePoolSize());
    }

    /** Aging credit A(W) in priority units for a task at {@code now}. */
    public double credit(Task task, Instant now) {
        long waitMillis = Math.max(0L, Duration.between(task.getCreatedAt(), now).toMillis());
        AgingProperties aging = aging();
        return aging.getPolicy().credit(waitMillis / 1000.0, aging.getLambda(), aging.getGamma());
    }

    /** Effective priority {@code P_base - A(W)}; tasks without a priority sort last. */
    public double effectivePriority(Task task, Instant now) {
        Long priority = task.getPriority();
        return priority == null ? Double.POSITIVE_INFINITY : priority - credit(task, now);
    }

    /**
     * Orders candidates by {@code (effective priority, arrival, id)} (invariants §15) and returns the best
     * {@code limit}.
     */
    public List<Task> rank(Collection<Task> candidates, int limit, Instant now) {
        Comparator<Task> order = Comparator
            .comparingDouble((Task task) -> effectivePriority(task, now))
            .thenComparing(Task::getCreatedAt)
            .thenComparing(Task::getId);
        return candidates.stream()
            .sorted(order)
            .limit(limit)
            .toList();
    }

    private AgingProperties aging() {
        return queueProperties.getAging();
    }
}
