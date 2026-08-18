package org.synanton.equalix.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.synanton.equalix.config.properties.AdaptiveRpsProperties;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Monitors remote executor latency and error rate via a sliding window and adjusts the allowed RPS.
 * The penalty factor derived here is used by PriorityCalculatorService to throttle busy clients
 * when the remote system is under stress.
 */
@Slf4j
@Component
public class AdaptiveRpsController {
    private final AdaptiveRpsProperties props;
    private final Deque<CompletionRecord> window;
    private final int windowSize;
    private final int minSamples;
    private final double latencyThreshold;
    private final double emergencyFactor;
    private final double decreaseFactor;
    private final double increaseFactor;
    private final double increaseErrorThreshold;
    private final double minRps;
    private final AtomicReference<Double> currentRps;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    public AdaptiveRpsController(AdaptiveRpsProperties props) {
        validate(props);
        this.props = props;
        this.minRps = props.getMinRps();
        this.minSamples = props.getMinSamples();
        this.currentRps = new AtomicReference<>(props.getInitialRps());
        this.windowSize = props.getWindowSize();
        this.latencyThreshold = props.getLatencyThreshold();
        this.window = new ArrayDeque<>(windowSize);
        this.emergencyFactor = props.getEmergencyFactor();
        this.decreaseFactor = props.getDecreaseFactor();
        this.increaseFactor = props.getIncreaseFactor();
        this.increaseErrorThreshold = props.getIncreaseErrorThreshold();
    }

    private void validate(AdaptiveRpsProperties props) {
        if (props.getInitialRps() <= 0) {
            throw new IllegalArgumentException("Initial RPS must be > 0: " + props.getInitialRps());
        }
        if (props.getMinSamples() <= 0) {
            throw new IllegalArgumentException("Min samples must be > 0: " + props.getMinSamples());
        }
        if (props.getLatencyThreshold() <= 0) {
            throw new IllegalArgumentException("Latency threshold must be > 0: " + props.getLatencyThreshold());
        }
        if (props.getIncreaseErrorThreshold() <= 0) {
            throw new IllegalArgumentException("Increase error threshold must be > 0: " +
                    props.getIncreaseErrorThreshold());
        }
        if (props.getLatencyThreshold() >= 1) {
            throw new IllegalArgumentException("Latency threshold must be < 1: " + props.getLatencyThreshold());
        }
        if (props.getErrorThreshold() < 0) {
            throw new IllegalArgumentException("Error threshold must be > 0: " + props.getErrorThreshold());
        }
        if (props.getErrorThreshold() > 1) {
            throw new IllegalArgumentException("Error threshold must be < 1: " + props.getErrorThreshold());
        }
        if (props.getWindowSize() <= 0) {
            throw new IllegalArgumentException("Window size must be > 0: " + props.getWindowSize());
        }
        if (props.getMaxRps() < props.getInitialRps()) {
            throw new IllegalArgumentException("Max RPS must be > initial RPS: " + props.getMaxRps());
        }
        if (props.getMinRps() <= 0) {
            throw new IllegalArgumentException("Min RPS must be > 0: " + props.getMinRps());
        }
        if (props.getTargetLatencyMs() <= 0) {
            throw new IllegalArgumentException("Target latency must be > 0: " + props.getTargetLatencyMs());
        }
    }

    public double getCurrentRps() {
        return currentRps.get();
    }

    /**
     * Returns the penalty factor used in priority calculation: 1000 / currentRps.
     */
    public double getPenaltyFactor() {
        return 1000.0 / currentRps.get();
    }

    public void recordCompletion(long durationMs, boolean success) {
        if (!props.isEnabled()) {
            if(!window.isEmpty()) {
                ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();
                writeLock.lock();
                try {
                    window.clear();
                } finally {
                    writeLock.unlock();
                }
            }
            return;
        }
        ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();
        writeLock.lock();
        try {
            if (window.size() >= windowSize) {
                window.pollFirst();
            }
            window.addLast(new CompletionRecord(durationMs, success));
            adjustRps();
        } finally {
            writeLock.unlock();
        }
    }

    private void adjustRps() {
        if (window.size() < minSamples || !props.isEnabled()) {
            return;
        }

        double sum = 0; long errors = 0;
        for (CompletionRecord r : window) {
            sum += r.durationMs();
            if (!r.success()) errors++;
        }
        double avgLatency = sum / window.size();
        double errorRate = (double) errors / window.size();

        double targetLatency = props.getTargetLatencyMs();

        if (errorRate > props.getErrorThreshold()) {
            currentRps.set(Math.max(minRps, currentRps.get() * emergencyFactor));
            log.info("Emergency RPS brake: error_rate={} rps={}", errorRate, currentRps);
        } else if (avgLatency > targetLatency * (1 + latencyThreshold)) {
            currentRps.set(Math.max(minRps, currentRps.get() * decreaseFactor));
            log.debug("RPS decreased due to high latency: avg_ms={} rps={}", avgLatency, currentRps);
        } else if (avgLatency < targetLatency * (1 - latencyThreshold) && errorRate < increaseErrorThreshold) {
            currentRps.set(Math.min(props.getMaxRps(), currentRps.get() * increaseFactor));
            log.debug("RPS increased: avg_ms={} rps={}", avgLatency, currentRps);
        }
    }

    private record CompletionRecord(long durationMs, boolean success) {
    }
}
