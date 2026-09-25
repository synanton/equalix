package org.synanton.equalix.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CmsErrorStatisticsTest {

    @Test
    void shouldReportZerosWithoutSamples() {
        CmsErrorStatistics statistics = new CmsErrorStatistics();

        assertThat(statistics.count()).isZero();
        assertThat(statistics.mean()).isZero();
        assertThat(statistics.max()).isZero();
        assertThat(statistics.min()).isZero();
        assertThat(statistics.percentileAbsolute(0.99)).isZero();
        assertThat(statistics.overestimationFrequency()).isZero();
    }

    @Test
    void shouldSummariseSignedErrors() {
        CmsErrorStatistics statistics = new CmsErrorStatistics();
        statistics.record(0, 6);
        statistics.record(2, 2);
        statistics.record(-1);
        statistics.record(5);

        assertThat(statistics.count()).isEqualTo(10);
        assertThat(statistics.mean()).isCloseTo(0.8, within(1e-12));
        assertThat(statistics.meanAbsolute()).isCloseTo(1.0, within(1e-12));
        assertThat(statistics.max()).isEqualTo(5);
        assertThat(statistics.min()).isEqualTo(-1);
        assertThat(statistics.maxAbsolute()).isEqualTo(5);
        assertThat(statistics.overestimationFrequency()).isCloseTo(0.3, within(1e-12));
        assertThat(statistics.underestimationFrequency()).isCloseTo(0.1, within(1e-12));
        assertThat(statistics.exactFrequency()).isCloseTo(0.6, within(1e-12));
        assertThat(statistics.histogram()).isEqualTo(Map.of(-1L, 1L, 0L, 6L, 2L, 2L, 5L, 1L));
    }

    @Test
    void shouldComputeNearestRankPercentilesOfMagnitude() {
        CmsErrorStatistics statistics = new CmsErrorStatistics();
        statistics.record(0, 94);
        statistics.record(-3, 1);
        statistics.record(3, 4);
        statistics.record(9);

        assertThat(statistics.percentileAbsolute(0.94)).isZero();
        assertThat(statistics.percentileAbsolute(0.95)).isEqualTo(3);
        assertThat(statistics.percentileAbsolute(0.99)).isEqualTo(3);
        assertThat(statistics.percentileAbsolute(1.0)).isEqualTo(9);
    }

    @Test
    void shouldMergeDistributions() {
        CmsErrorStatistics first = new CmsErrorStatistics();
        first.record(1, 3);
        CmsErrorStatistics second = new CmsErrorStatistics();
        second.record(-2, 1);

        first.merge(second);

        assertThat(first.histogram()).isEqualTo(Map.of(-2L, 1L, 1L, 3L));
        assertThat(first.mean()).isCloseTo(0.25, within(1e-12));
    }
}
