package org.synanton.equalix.domain.port.out;

import java.util.Optional;
import org.synanton.equalix.domain.model.VirtualTimeState;

/**
 * Outgoing port for durable weighted virtual time. All updates are atomic and monotonic so that they are
 * safe under concurrent scheduler instances and survive restarts.
 */
public interface VirtualTimeRepositoryPort {

    /** Returns the system virtual time V, or 0 when nothing has been dispatched yet. */
    double findSystemVirtualTime();

    /**
     * Atomically assigns the next finish tag for a key: {@code max(virtualFinish_k, systemVirtualTime) + increment}.
     * Creates the key state when absent.
     *
     * @return the assigned finish tag, which is also the key's new {@code virtualFinish}
     */
    double reserveFinishTag(String fairnessKey, double systemVirtualTime, double increment);

    /** Advances T_k to {@code finishTag} if it is ahead of the stored value. */
    void advanceClientVirtualTime(String fairnessKey, double finishTag);

    /** Advances the system virtual time V to {@code finishTag} if it is ahead of the stored value. */
    void advanceSystemVirtualTime(double finishTag);

    Optional<VirtualTimeState> findByFairnessKey(String fairnessKey);
}
