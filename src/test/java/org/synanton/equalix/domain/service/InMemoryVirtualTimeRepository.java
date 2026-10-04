package org.synanton.equalix.domain.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.synanton.equalix.domain.model.VirtualTimeState;
import org.synanton.equalix.domain.port.out.VirtualTimeRepositoryPort;

/** In-memory {@link VirtualTimeRepositoryPort} with the same semantics as the PostgreSQL adapter. */
final class InMemoryVirtualTimeRepository implements VirtualTimeRepositoryPort {

    private final Map<String, VirtualTimeState> states = new HashMap<>();
    private final Instant updatedAt;
    private double systemVirtualTime;

    InMemoryVirtualTimeRepository(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public double findSystemVirtualTime() {
        return systemVirtualTime;
    }

    @Override
    public double reserveFinishTag(String fairnessKey, double systemVirtualTimeFloor, double increment) {
        VirtualTimeState state = states.computeIfAbsent(fairnessKey, key -> new VirtualTimeState()
            .setFairnessKey(key)
            .setVirtualTime(systemVirtualTimeFloor)
            .setVirtualFinish(systemVirtualTimeFloor)
            .setUpdatedAt(updatedAt));
        state.setVirtualFinish(Math.max(state.getVirtualFinish(), systemVirtualTimeFloor) + increment);
        return state.getVirtualFinish();
    }

    @Override
    public void advanceClientVirtualTime(String fairnessKey, double finishTag) {
        VirtualTimeState state = states.get(fairnessKey);
        state.setVirtualTime(Math.max(state.getVirtualTime(), finishTag))
            .setVirtualFinish(Math.max(state.getVirtualFinish(), finishTag));
    }

    @Override
    public void advanceSystemVirtualTime(double finishTag) {
        systemVirtualTime = Math.max(systemVirtualTime, finishTag);
    }

    @Override
    public Optional<VirtualTimeState> findByFairnessKey(String fairnessKey) {
        return Optional.ofNullable(states.get(fairnessKey));
    }
}
