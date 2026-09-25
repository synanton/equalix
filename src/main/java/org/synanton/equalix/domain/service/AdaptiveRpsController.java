package org.synanton.equalix.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.synanton.equalix.config.properties.AdaptiveRpsProperties;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Monitors remote executor latency and error rate via a sliding window and adjusts the allowed RPS.
 * The penalty factor derived here is used by PriorityCalculatorService to throttle busy clients
 * when the remote system is under stress.
 *
 * <p>Stability controls (EQX-6, invariants §25.7):
 * <ul>
 *   <li><b>Adjustment interval</b>: RPS changes at most once per {@code adjustment-interval-ms}. Without it,
 *       every completion re-applies the step to the same window, so one latency spike compounds into many
 *       consecutive decreases.</li>
 *   <li><b>Latency EMA</b>: each evaluation's latency, the mean of the completions since the previous evaluation,
 *       is smoothed as {@code smoothed = α·mean + (1−α)·smoothed}; {@code α = 1} disables smoothing. With an
 *       interval of 0 the controller keeps the pre-EQX-6 signal, the mean of the whole window.</li>
 *   <li><b>Direction dampener</b>: reversing direction (increase after decrease, or the opposite) requires
 *       {@code direction-change-confirmations} consecutive evaluations that agree; 1 reverses immediately.
 *       Evaluations inside the latency dead band reset the count.</li>
 * </ul>
 * The emergency brake on a high error rate is never dampened, but it is limited by the adjustment interval.
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
    private final double latencyEmaAlpha;
    private final long adjustmentIntervalMs;
    private final int directionChangeConfirmations;
    private final Clock clock;
    private final AtomicReference<Double> currentRps;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    // Guarded by rwLock's write lock.
    private double smoothedLatency = Double.NaN;
    private long lastEvaluationMillis;
    private boolean evaluatedBefore;
    private Direction lastDirection = Direction.NONE;
    private int pendingReversals;
    // Latency of completions recorded since the previous evaluation.
    private double freshLatencySum;
    private int freshSamples;

    public AdaptiveRpsController(AdaptiveRpsProperties props, Clock clock) {
        validate(props);
        this.props = props;
        this.clock = clock;
        this.latencyEmaAlpha = props.getLatencyEmaAlpha();
        this.adjustmentIntervalMs = props.getAdjustmentIntervalMs();
        this.directionChangeConfirmations = props.getDirectionChangeConfirmations();
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
        if (props.getLatencyEmaAlpha() <= 0 || props.getLatencyEmaAlpha() > 1) {
            throw new IllegalArgumentException("Latency EMA alpha must be in (0, 1]: " + props.getLatencyEmaAlpha());
        }
        if (props.getAdjustmentIntervalMs() < 0) {
            throw new IllegalArgumentException("Adjustment interval must be >= 0: " + props.getAdjustmentIntervalMs());
        }
        if (props.getDirectionChangeConfirmations() < 1) {
            throw new IllegalArgumentException("Direction change confirmations must be >= 1: "
                + props.getDirectionChangeConfirmations());
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
                    resetStability();
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
            freshLatencySum += durationMs;
            freshSamples++;
            adjustRps();
        } finally {
            writeLock.unlock();
        }
    }

    private void adjustRps() {
        if (window.size() < minSamples || !props.isEnabled()) {
            return;
        }
        long now = clock.millis();
        if (evaluatedBefore && now - lastEvaluationMillis < adjustmentIntervalMs) {
            return;
        }
        evaluatedBefore = true;
        lastEvaluationMillis = now;

        double sum = 0;
        long errors = 0;
        for (CompletionRecord record : window) {
            sum += record.durationMs();
            if (!record.success()) {
                errors++;
            }
        }
        // With an adjustment interval, the latency signal is the mean of the samples since the previous evaluation
        // and the EMA carries the history, so the smoothing time constant (interval / alpha) does not depend on
        // the completion rate. Re-averaging the whole count-based window on every evaluation would re-count stale
        // samples, which at low RPS span many seconds. Interval 0 keeps the pre-EQX-6 whole-window mean.
        double windowLatency = adjustmentIntervalMs == 0 || freshSamples == 0
            ? sum / window.size()
            : freshLatencySum / freshSamples;
        freshLatencySum = 0;
        freshSamples = 0;
        double errorRate = (double) errors / window.size();
        smoothedLatency = Double.isNaN(smoothedLatency)
            ? windowLatency
            : latencyEmaAlpha * windowLatency + (1 - latencyEmaAlpha) * smoothedLatency;

        double targetLatency = props.getTargetLatencyMs();
        if (errorRate > props.getErrorThreshold()) {
            // Safety first: the brake is never delayed by the dampener.
            currentRps.set(Math.max(minRps, currentRps.get() * emergencyFactor));
            lastDirection = Direction.DOWN;
            pendingReversals = 0;
            log.info("Emergency RPS brake: error_rate={} rps={}", errorRate, currentRps);
            return;
        }

        Direction proposed;
        if (smoothedLatency > targetLatency * (1 + latencyThreshold)) {
            proposed = Direction.DOWN;
        } else if (smoothedLatency < targetLatency * (1 - latencyThreshold) && errorRate < increaseErrorThreshold) {
            proposed = Direction.UP;
        } else {
            pendingReversals = 0;
            return;
        }

        if (lastDirection != Direction.NONE && proposed != lastDirection
            && ++pendingReversals < directionChangeConfirmations) {
            log.debug("RPS {} dampened ({}/{}): smoothed_ms={} rps={}", proposed, pendingReversals,
                directionChangeConfirmations, smoothedLatency, currentRps);
            return;
        }
        pendingReversals = 0;
        lastDirection = proposed;
        if (proposed == Direction.DOWN) {
            currentRps.set(Math.max(minRps, currentRps.get() * decreaseFactor));
            log.debug("RPS decreased due to high latency: smoothed_ms={} rps={}", smoothedLatency, currentRps);
        } else {
            currentRps.set(Math.min(props.getMaxRps(), currentRps.get() * increaseFactor));
            log.debug("RPS increased: smoothed_ms={} rps={}", smoothedLatency, currentRps);
        }
    }

    private void resetStability() {
        smoothedLatency = Double.NaN;
        freshLatencySum = 0;
        freshSamples = 0;
        evaluatedBefore = false;
        lastDirection = Direction.NONE;
        pendingReversals = 0;
    }

    private enum Direction { NONE, UP, DOWN }

    private record CompletionRecord(long durationMs, boolean success) {
    }
}
