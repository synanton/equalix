package org.synanton.equalix.config.properties;

import jakarta.validation.Valid;
import lombok.Data;
import org.synanton.equalix.domain.model.FairnessMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

/** Root configuration for queue scheduling behaviour. */
@Data
@Validated
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

    /** {@code flat} (default) or {@code hierarchical}; see {@link HierarchicalProperties}. */
    private FairnessMode fairnessMode;

    @NestedConfigurationProperty
    private CmsProperties cms = new CmsProperties();

    @NestedConfigurationProperty
    private VirtualTimeProperties virtualTime = new VirtualTimeProperties();

    @Valid
    @NestedConfigurationProperty
    private AgingProperties aging = new AgingProperties();
}
