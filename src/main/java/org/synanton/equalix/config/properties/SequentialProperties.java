package org.synanton.equalix.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the sequential execution extension. */
@Data
@ConfigurationProperties(prefix = "app.queue.sequential")
public class SequentialProperties {

    private boolean enabled;
    private long clientBlockTimeoutMs;
    private long dispatcherInterval;
    private long blockRecoveryInterval;
    private long resultPassthroughInterval;
}
