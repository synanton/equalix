package org.synanton.equalix.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the watchdog reconciliation job. */
@Data
@ConfigurationProperties(prefix = "app.watchdog")
public class WatchdogProperties {

    private long intervalMinutes;

    /**
     * Maximum number of fairness keys exported as {@code equalix.cms.estimation.drift{fairnessKey}} per run,
     * largest {@code |drift|} first. Bounds Prometheus series cardinality; aggregates always cover every key.
     */
    private int driftMetricMaxKeys;
}
