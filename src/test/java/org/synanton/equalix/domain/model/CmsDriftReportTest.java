package org.synanton.equalix.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CmsDriftReportTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:05:00Z");

    @Test
    void shouldAggregateOverAllKeysAndKeepLargestDriftFirst() {
        Map<String, Long> drift = Map.of("exact", 0L, "over", 3L, "under", -5L, "slightlyOver", 1L, "tieB", 3L);

        CmsDriftReport report = CmsDriftReport.of(drift, 10, NOW);

        assertThat(report).isEqualTo(new CmsDriftReport(NOW, 5, 4, 3, -5, 12,
            ordered("under", -5L, "over", 3L, "tieB", 3L, "slightlyOver", 1L),
            Map.of("under", "key", "over", "key", "tieB", "key", "slightlyOver", "key"), Map.of("key", 12L)));
        assertThat(List.copyOf(report.topDrifting().keySet()))
            .containsExactly("under", "over", "tieB", "slightlyOver");
    }

    @Test
    void shouldCapExportedKeysButNotAggregates() {
        Map<String, Long> drift = Map.of("a", 1L, "b", -2L, "c", 3L);

        CmsDriftReport report = CmsDriftReport.of(drift, 2, NOW);

        assertThat(report).isEqualTo(new CmsDriftReport(NOW, 3, 3, 3, -2, 6, ordered("c", 3L, "b", -2L),
            Map.of("c", "key", "b", "key"), Map.of("key", 6L)));
    }

    @Test
    void shouldReportZerosWhenNothingDrifts() {
        CmsDriftReport report = CmsDriftReport.of(Map.of("a", 0L), 10, NOW);

        assertThat(report).isEqualTo(new CmsDriftReport(NOW, 1, 0, 0, 0, 0, Map.of(), Map.of(), Map.of("key", 0L)));
    }

    @Test
    void shouldExposeImmutableTopDrifting() {
        CmsDriftReport report = CmsDriftReport.of(Map.of("a", 1L), 10, NOW);

        assertThatThrownBy(() -> report.topDrifting().put("b", 2L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldGroupAbsoluteDriftByLayer() {
        Map<String, Long> drift = Map.of("acme/", 3L, "acme/sales", -3L, "acme/it", 0L, "", 0L);
        Map<String, String> layers = Map.of("acme/", "organization", "acme/sales", "department",
            "acme/it", "department", "", "root");

        CmsDriftReport report = CmsDriftReport.of(drift, 10, NOW, layers::get);

        assertThat(report.absoluteDriftByLayer()).isEqualTo(Map.of("organization", 3L, "department", 3L, "root", 0L));
        assertThat(report.layers()).isEqualTo(Map.of("acme/", "organization", "acme/sales", "department"));
    }

    private static Map<String, Long> ordered(Object... keysAndValues) {
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            ordered.put((String) keysAndValues[index], (Long) keysAndValues[index + 1]);
        }
        return ordered;
    }
}
