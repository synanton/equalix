package org.synanton.equalix.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/** Configuration for the Count-Min Sketch (local or Redis-backed). */
@Data
@ConfigurationProperties(prefix = "app.cms")
public class CmsProperties {

    /** Number of columns in the CMS matrix; larger values reduce error magnitude (ε = 2/width). */
    private int width;

    /** Number of hash rows; larger values reduce error probability (δ = (1/2)^depth). */
    private int depth;

    /**
     * CMS adapter mode: {@code local} (default, in-memory sketch) or
     * {@code redis} (distributed, shared across all instances).
     */
    private String mode = "local";

    @NestedConfigurationProperty
    private RedisProperties redis = new RedisProperties();

    @Data
    public static class RedisProperties {

        /** Redis hash key used to store the CMS matrix. */
        private String keyNamespace = "equalix:cms";

        /** Fall back to the local in-memory CMS if Redis is unreachable. */
        private boolean fallbackToLocal = true;
    }
}
