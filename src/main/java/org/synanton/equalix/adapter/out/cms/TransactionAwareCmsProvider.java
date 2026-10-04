package org.synanton.equalix.adapter.out.cms;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.synanton.equalix.domain.port.out.CMSProviderPort;

/**
 * Makes CMS updates follow the outcome of the surrounding database transaction.
 *
 * <p>Inside a transaction, {@link #add} only buffers the delta. The buffered net deltas are applied after commit
 * and discarded on rollback, so a rolled-back dispatch no longer leaves a phantom +1 and a rolled-back completion
 * that is retried no longer subtracts twice (invariants §20, EQX-2). Outside a transaction, deltas apply
 * immediately.
 *
 * <p>Reads and {@link #rebuild} go straight to the sketch; a transaction does not see its own buffered deltas,
 * which no caller relies on. The buffer lives in a transaction synchronization, so a suspended outer transaction
 * and an inner {@code REQUIRES_NEW} transaction keep separate buffers.
 */
@Slf4j
@RequiredArgsConstructor
public class TransactionAwareCmsProvider implements CMSProviderPort {

    private final CMSProviderPort delegate;

    @Override
    public void add(String key, long delta) {
        if (delta == 0) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            delegate.add(key, delta);
            return;
        }
        pendingDeltas().merge(key, delta, Long::sum);
    }

    @Override
    public long estimateCount(String key) {
        return delegate.estimateCount(key);
    }

    @Override
    public void rebuild(Map<String, Integer> snapshot) {
        delegate.rebuild(snapshot);
    }

    @Override
    public long totalInFlight() {
        return delegate.totalInFlight();
    }

    private Map<String, Long> pendingDeltas() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof PendingDeltas pending && pending.owner() == this) {
                return pending.deltas();
            }
        }
        PendingDeltas pending = new PendingDeltas(this, new LinkedHashMap<>());
        TransactionSynchronizationManager.registerSynchronization(pending);
        return pending.deltas();
    }

    private void apply(Map<String, Long> deltas) {
        deltas.forEach((key, delta) -> {
            if (delta == 0) {
                return;
            }
            try {
                delegate.add(key, delta);
            } catch (RuntimeException exception) {
                // The transaction is already committed; the watchdog repairs the sketch.
                log.warn("CMS update after commit failed for key='{}' delta={}: {}", key, delta,
                    exception.getMessage());
            }
        });
    }

    /** Identity-based on purpose: Spring keeps synchronizations in a hash set while the buffer keeps changing. */
    private static final class PendingDeltas implements TransactionSynchronization {

        private final TransactionAwareCmsProvider owner;
        private final Map<String, Long> deltas;

        PendingDeltas(TransactionAwareCmsProvider owner, Map<String, Long> deltas) {
            this.owner = owner;
            this.deltas = deltas;
        }

        TransactionAwareCmsProvider owner() {
            return owner;
        }

        Map<String, Long> deltas() {
            return deltas;
        }

        @Override
        public void afterCommit() {
            owner.apply(deltas);
        }
    }
}
