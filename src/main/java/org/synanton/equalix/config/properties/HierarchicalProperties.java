package org.synanton.equalix.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Tree definition for {@code app.queue.fairness-mode: hierarchical} (EQX-7). */
@Data
@Validated
@ConfigurationProperties(prefix = "app.hierarchical")
public class HierarchicalProperties {

    /** Separates the segments of a fairness key, e.g. {@code acme/sales}. */
    @NotBlank
    private String separator;

    /**
     * Layers from the root down. A key with more segments than layers folds the remaining segments into the last
     * layer; a key with fewer segments is a leaf at a higher layer.
     */
    @Valid
    @NotEmpty
    private List<Layer> layers = new ArrayList<>();

    /**
     * Weight overrides by node path without a trailing separator, e.g. {@code "[acme]": 2.0} or
     * {@code "[acme/sales]": 3.0}. Keys containing the separator need bracket notation in YAML.
     */
    private Map<String, @Positive Double> weights = new LinkedHashMap<>();

    /** Layers, from the root, whose nodes get a {@code equalix.hierarchy.dispatches} counter; 0 disables. */
    @PositiveOrZero
    private int metricsDepth;

    @Data
    public static class Layer {

        @NotBlank
        private String name;

        /** Weight of nodes in this layer that have no override. Leaves use the task weight instead. */
        @Positive
        private double defaultWeight;
    }
}
