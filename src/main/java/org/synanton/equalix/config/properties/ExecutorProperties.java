package org.synanton.equalix.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** HTTP remote executor connection settings. */
@Data
@ConfigurationProperties(prefix = "app.executor")
public class ExecutorProperties {

    private String baseUrl;
    private int connectTimeoutMs;
    private int readTimeoutMs;
}
