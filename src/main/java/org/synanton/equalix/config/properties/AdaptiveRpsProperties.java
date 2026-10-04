package org.synanton.equalix.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the adaptive RPS controller. */
@Data
@ConfigurationProperties(prefix = "app.adaptive-rps")
public class AdaptiveRpsProperties {

    private boolean enabled;
    private double initialRps;
    private double minRps;
    private double maxRps;
    private long targetLatencyMs;
    private double errorThreshold;
    private double latencyThreshold;
    private int windowSize;
    private int minSamples;
    private double emergencyFactor;
    private double decreaseFactor;
    private double increaseFactor;
    private double increaseErrorThreshold;

    /** EMA weight of the newest window mean in (0, 1]; 1 disables smoothing. */
    private double latencyEmaAlpha;

    /** Minimum time between two RPS adjustments; 0 adjusts on every completion (pre-EQX-6 behaviour). */
    private long adjustmentIntervalMs;

    /** Consecutive agreeing evaluations required to reverse direction; 1 reverses immediately. */
    private int directionChangeConfirmations;
}
