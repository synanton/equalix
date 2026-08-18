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
}
