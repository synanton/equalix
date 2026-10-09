package org.synanton.equalix.config;

import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

/**
 * Lock provider for single-instance deployments ({@code app.scheduling.distributed-locks=false}).
 * Always reports the lock as held without touching the database, eliminating ShedLock's
 * acquire/release round-trips on every scheduler tick (~140 statements/s across the fast
 * schedulers at default cadences). Only correct with exactly one scheduler instance —
 * with two or more, ticks would run concurrently on every instance.
 */
public class NoOpLockProvider implements LockProvider {

    @Override
    public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
        return Optional.of(() -> {
        });
    }
}
