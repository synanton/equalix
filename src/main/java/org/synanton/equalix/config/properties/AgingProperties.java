package org.synanton.equalix.config.properties;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import org.synanton.equalix.domain.model.AgingPolicy;

/** Configuration for anti-starvation aging A(W), evaluated by the dispatcher at selection time. */
@Data
public class AgingProperties {

    /** Aging function: {@code none}, {@code linear}, {@code log} or {@code power}. */
    private AgingPolicy policy;

    /** Aging rate λ in priority units (one weight-1 task advances virtual time by {@code virtual-time.quantum}). */
    @PositiveOrZero
    private double lambda;

    /** Exponent γ for the {@code power} policy. */
    @Positive
    private double gamma;

    /**
     * Rows the dispatcher locks from each ordering (best base priority, oldest arrival) before re-ranking by aged
     * priority. Larger pools rank more exactly at the cost of locking more rows per tick.
     */
    @Positive
    private int candidatePoolSize;
}
