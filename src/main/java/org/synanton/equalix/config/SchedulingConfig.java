package org.synanton.equalix.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the {@code @Scheduled} jobs in {@code adapter.in.schedule}. Integration tests turn this off so they can
 * drive the pipeline deterministically.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.scheduling", name = "enabled", havingValue = "true")
public class SchedulingConfig {
}
