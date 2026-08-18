package org.synanton.equalix.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the watchdog reconciliation job. */
@Data
@ConfigurationProperties(prefix = "app.watchdog")
public class WatchdogProperties {

    private long intervalMinutes;
}
