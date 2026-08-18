package org.synanton.equalix.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** API authentication settings. */
@Data
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    /** Shared secret expected in the X-API-Key header. Override via EQUALIX_API_KEY in production. */
    private String apiKey;
}
