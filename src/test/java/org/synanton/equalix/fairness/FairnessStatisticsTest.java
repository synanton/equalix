package org.synanton.equalix.fairness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FairnessStatisticsTest {

    private final FairnessStatistics statistics = new FairnessStatistics(weights());

    @Test
    void shouldDeriveExpectedSharesFromWeights() {
        assertThat(statistics.expectedShares()).isEqualTo(Map.of("A", 0.25, "B", 0.75));
    }

    @Test
    void shouldMeasurePrefixSharesAndError() {
        List<String> sequence = List.of("A", "A", "B", "B", "B", "B", "B", "B");

        assertThat(statistics.observedShares(sequence, 4)).isEqualTo(Map.of("A", 0.5, "B", 0.5));
        assertThat(statistics.prefixMaxError(sequence, 4)).isCloseTo(0.25, within(1e-12));
        assertThat(statistics.prefixMaxError(sequence, 8)).isCloseTo(0.0, within(1e-12));
    }

    @Test
    void shouldReportWorstSlidingWindow() {
        // Every prefix of length 8 is perfect, but the window [2, 6) contains no "A" at all.
        List<String> sequence = List.of("A", "B", "B", "B", "B", "B", "A", "B");

        assertThat(statistics.slidingMaxError(sequence, 4)).isCloseTo(0.25, within(1e-12));
        assertThat(statistics.slidingMaxError(sequence, 8)).isCloseTo(0.0, within(1e-12));
    }

    private static Map<String, BigDecimal> weights() {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        weights.put("A", BigDecimal.ONE);
        weights.put("B", new BigDecimal("3"));
        return weights;
    }
}
