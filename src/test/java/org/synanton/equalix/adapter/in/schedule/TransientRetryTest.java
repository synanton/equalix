package org.synanton.equalix.adapter.in.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;

class TransientRetryTest {

    @Test
    void retriesLockFailuresAndSucceeds() {
        AtomicInteger calls = new AtomicInteger();

        TransientRetry.run("test", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new DeadlockLoserDataAccessException("deadlock", new RuntimeException("db"));
            }
        });

        assertThat(calls).hasValue(3);
    }

    @Test
    void propagatesAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> TransientRetry.run("test", () -> {
            calls.incrementAndGet();
            throw new DeadlockLoserDataAccessException("deadlock", new RuntimeException("db"));
        })).isInstanceOf(DeadlockLoserDataAccessException.class);
        assertThat(calls).hasValue(3);
    }

    @Test
    void doesNotRetryBusinessFailures() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> TransientRetry.run("test", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
    }
}
