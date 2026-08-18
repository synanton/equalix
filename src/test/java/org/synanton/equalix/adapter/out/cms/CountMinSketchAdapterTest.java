package org.synanton.equalix.adapter.out.cms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.equalix.config.properties.CmsProperties;
import org.synanton.equalix.config.properties.QueueProperties;

class CountMinSketchAdapterTest {

    private CountMinSketchAdapter adapter;

    @BeforeEach
    void setUp() {
        QueueProperties props = new QueueProperties();
        CmsProperties cmsProps = new CmsProperties();
        cmsProps.setWidth(1024);
        cmsProps.setDepth(3);
        props.setCms(cmsProps);
        adapter = new CountMinSketchAdapter(props);
    }

    @Test
    void shouldReturnZeroForUnknownKey() {
        assertThat(adapter.estimateCount("unknown-client")).isEqualTo(0L);
    }

    @Test
    void shouldIncrementAndEstimateCount() {
        adapter.add("clientA", 1);
        adapter.add("clientA", 1);
        adapter.add("clientA", 1);

        assertThat(adapter.estimateCount("clientA")).isGreaterThanOrEqualTo(3L);
    }

    @Test
    void shouldDecrementCountOnNegativeDelta() {
        adapter.add("clientA", 3);
        adapter.add("clientA", -1);

        assertThat(adapter.estimateCount("clientA")).isEqualTo(2L);
    }

    @Test
    void shouldReturnAtLeastZeroAfterDecrementBelowZero() {
        adapter.add("clientA", -1);

        assertThat(adapter.estimateCount("clientA")).isEqualTo(0L);
    }

    @Test
    void shouldRebuildFromSnapshot() {
        adapter.add("clientA", 5);

        adapter.rebuild(Map.of("clientB", 7));

        assertThat(adapter.estimateCount("clientB")).isGreaterThanOrEqualTo(7L);
        // clientA was not in snapshot so its estimate resets
        assertThat(adapter.estimateCount("clientA")).isEqualTo(0L);
    }

    @Test
    void shouldTrackMultipleClientsIndependently() {
        adapter.add("clientA", 3);
        adapter.add("clientB", 1);

        assertThat(adapter.estimateCount("clientA")).isGreaterThanOrEqualTo(3L);
        assertThat(adapter.estimateCount("clientB")).isGreaterThanOrEqualTo(1L);
    }
}
