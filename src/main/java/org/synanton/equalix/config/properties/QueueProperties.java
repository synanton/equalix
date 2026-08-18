package org.synanton.equalix.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/** Root configuration for queue scheduling behaviour. */
@Data
@ConfigurationProperties(prefix = "app.queue")
public class QueueProperties {

    private int maxTasksInProcess;
    private int maxPerClientQuota;
    private long priorityCalcInterval;
    private long dispatcherInterval;
    private int workerPollSize;
    private long maxQueuedTimeMs;
    private long taskTimeoutMs;
    private int maxPayloadBytes;

    @NestedConfigurationProperty
    private CmsProperties cms = new CmsProperties();
}
