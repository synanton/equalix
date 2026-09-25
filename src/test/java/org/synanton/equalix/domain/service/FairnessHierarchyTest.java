package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.synanton.equalix.config.properties.HierarchicalProperties;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.FairnessMode;
import org.synanton.equalix.domain.model.HierarchyNode;

public class FairnessHierarchyTest {

    private final FairnessHierarchy hierarchy = hierarchy(FairnessMode.HIERARCHICAL, Map.of("acme", 2.0));

    @Test
    void shouldMapKeyOntoOrganizationAndDepartment() {
        assertThat(hierarchy.path("acme/sales")).containsExactly(
            new HierarchyNode("acme/", "", 0, "organization", false),
            new HierarchyNode("acme/sales", "acme/", 1, "department", true));
    }

    @Test
    void shouldFoldExtraSegmentsIntoLastLayer() {
        assertThat(hierarchy.path("acme/sales/bob")).containsExactly(
            new HierarchyNode("acme/", "", 0, "organization", false),
            new HierarchyNode("acme/sales/bob", "acme/", 1, "department", true));
    }

    @Test
    void shouldPlaceSingleSegmentKeyAsLeafAtRootLayer() {
        assertThat(hierarchy.path("smallclub"))
            .containsExactly(new HierarchyNode("smallclub", "", 0, "organization", true));
        assertThat(hierarchy.internalNodeKeys("smallclub")).isEmpty();
    }

    @Test
    void shouldUseOverrideThenTaskWeightForLeavesAndLayerDefaultForInternalNodes() {
        List<HierarchyNode> path = hierarchy.path("acme/sales");
        List<HierarchyNode> other = hierarchy.path("globex/it");

        assertThat(List.of(hierarchy.weight(path.get(0), 5.0), hierarchy.weight(path.get(1), 5.0),
            hierarchy.weight(other.get(0), 5.0))).containsExactly(2.0, 5.0, 1.0);
    }

    @Test
    void shouldAddInternalNodeAndRootCounts() {
        Map<String, Integer> expanded = hierarchy.withAncestors(Map.of("acme/sales", 3, "acme/it", 2, "club", 4));

        assertThat(expanded).isEqualTo(Map.of("acme/sales", 3, "acme/it", 2, "club", 4, "acme/", 5, "", 9));
    }

    @Test
    void shouldNameLayersOfNodeKeys() {
        assertThat(List.of(hierarchy.layerOf(""), hierarchy.layerOf("acme/"), hierarchy.layerOf("acme/sales"),
            hierarchy.layerOf("club"))).containsExactly("root", "organization", "department", "organization");
    }

    @Test
    void shouldRejectKeysWithEmptySegments() {
        assertThat(hierarchy.validationError("acme/sales")).isNull();
        assertThat(List.of("acme/", "/acme", "acme//sales"))
            .allSatisfy(key -> assertThat(hierarchy.validationError(key)).isNotNull());
    }

    @Test
    void shouldTreatEveryKeyAsIndependentLeafInFlatMode() {
        FairnessHierarchy flat = hierarchy(FairnessMode.FLAT, Map.of());

        assertThat(flat.path("acme/sales")).containsExactly(new HierarchyNode("acme/sales", "", 0, "key", true));
        assertThat(flat.withAncestors(Map.of("acme/sales", 3))).isEqualTo(Map.of("acme/sales", 3));
        assertThat(flat.validationError("acme//")).isNull();
        assertThat(flat.layerOf("acme/sales")).isEqualTo("key");
    }

    @Test
    void shouldRequireLayersInHierarchicalMode() {
        HierarchicalProperties props = new HierarchicalProperties();
        props.setSeparator("/");
        QueueProperties queue = new QueueProperties();
        queue.setFairnessMode(FairnessMode.HIERARCHICAL);

        assertThatThrownBy(() -> new FairnessHierarchy(queue, props)).isInstanceOf(IllegalStateException.class);
    }

    public static FairnessHierarchy hierarchy(FairnessMode mode, Map<String, Double> weights) {
        HierarchicalProperties props = new HierarchicalProperties();
        props.setSeparator("/");
        props.setLayers(List.of(layer("organization"), layer("department")));
        props.setWeights(weights);
        props.setMetricsDepth(1);
        QueueProperties queue = new QueueProperties();
        queue.setFairnessMode(mode);
        return new FairnessHierarchy(queue, props);
    }

    private static HierarchicalProperties.Layer layer(String name) {
        HierarchicalProperties.Layer layer = new HierarchicalProperties.Layer();
        layer.setName(name);
        layer.setDefaultWeight(1.0);
        return layer;
    }
}
