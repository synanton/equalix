package org.synanton.equalix.adapter.in.schedule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Bounded retry for scheduler ticks aborted by lock contention (P1). Concurrent ticks take
 * row locks in deterministic order (see {@code DispatcherService}), so a deadlock cycle
 * cannot form between same-shape ticks — but residual cross-shape races (e.g. calculator
 * virtual-time-then-tasks vs dispatcher tasks-then-virtual-time) can still lose a deadlock
 * lottery. Those ticks are idempotent (re-selection sees the same backlog), so retrying
 * the whole tick is safe; the next scheduled tick is the final backstop either way.
 *
 * <p>Only pessimistic lock failures retry here. Optimistic conflicts surface as rowcount
 * mismatches handled by the services themselves, and business failures must never retry.
 */
@Slf4j
final class TransientRetry {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MS = 10L;

    private TransientRetry() {
    }

    static void run(String job, Runnable tick) {
        PessimisticLockingFailureException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                tick.run();
                return;
            } catch (PessimisticLockingFailureException e) {
                last = e;
                log.warn("Scheduler tick {} lost a lock race (attempt {}/{}), retrying",
                    job, attempt, MAX_ATTEMPTS);
                try {
                    Thread.sleep(BACKOFF_MS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }
}
