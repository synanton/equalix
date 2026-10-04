package org.synanton.equalix.adapter.out.cms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.FairnessMode;
import org.synanton.equalix.domain.service.FairnessHierarchyTest;

class HierarchicalCmsProviderTest {

    private final CountMinSketchAdapter sketch = new CountMinSketchAdapter(sketchProps());
    private final HierarchicalCmsProvider provider = new HierarchicalCmsProvider(sketch,
        FairnessHierarchyTest.hierarchy(FairnessMode.HIERARCHICAL, Map.of()));

    @Test
    void shouldCountEveryInternalNodeAndRoot() {
        provider.add("acme/sales", 3);
        provider.add("acme/it", 2);
        provider.add("small", 4);
        provider.add("acme/it", -1);

        assertThat(List.of(provider.estimateCount("acme/sales"), provider.estimateCount("acme/it"),
            provider.estimateCount("acme/"), provider.estimateCount("small"), provider.estimateCount("")))
            .containsExactly(3L, 1L, 4L, 4L, 8L);
    }

    @Test
    void shouldReportTotalFromRootRatherThanInflatedSketchTotal() {
        provider.add("acme/sales", 3);

        assertThat(provider.totalInFlight()).isEqualTo(3);
        assertThat(sketch.totalInFlight()).isEqualTo(9); // leaf + acme/ + root
    }

    @Test
    void shouldExpandSnapshotWithAncestorsOnRebuild() {
        provider.add("stale/key", 7);

        provider.rebuild(Map.of("acme/sales", 3, "acme/it", 2));

        assertThat(List.of(provider.estimateCount("acme/"), provider.estimateCount(""),
            provider.estimateCount("stale/key"))).containsExactly(5L, 5L, 0L);
    }

    private static QueueProperties sketchProps() {
        QueueProperties props = new QueueProperties();
        props.getCms().setWidth(65_536);
        props.getCms().setDepth(5);
        return props;
    }
}
