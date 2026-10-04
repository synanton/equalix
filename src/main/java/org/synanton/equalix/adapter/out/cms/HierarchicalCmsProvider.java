package org.synanton.equalix.adapter.out.cms;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.service.FairnessHierarchy;

/**
 * Multi-layer in-flight accounting for hierarchical mode (EQX-7): every update of a fairness key is also applied
 * to each internal node above it (e.g. {@code acme/}) and to the root ({@code ""}), in the same sketch.
 *
 * <p>Memory stays fixed. The sketch holds at most layers + 1 times as many non-zero entries, and its
 * overestimate bound {@code 2N/w} grows by the same factor (invariants §20). The total in flight is the root's
 * count, because the sketch's own total now includes every ancestor update.
 */
@RequiredArgsConstructor
public class HierarchicalCmsProvider implements CMSProviderPort {

    private final CMSProviderPort delegate;
    private final FairnessHierarchy hierarchy;

    @Override
    public void add(String key, long delta) {
        delegate.add(key, delta);
        hierarchy.internalNodeKeys(key).forEach(node -> delegate.add(node, delta));
        delegate.add(FairnessHierarchy.ROOT, delta);
    }

    @Override
    public long estimateCount(String key) {
        return delegate.estimateCount(key);
    }

    /** Expands a per-fairness-key snapshot with internal node and root counts before rebuilding. */
    @Override
    public void rebuild(Map<String, Integer> snapshot) {
        delegate.rebuild(hierarchy.withAncestors(snapshot));
    }

    @Override
    public long totalInFlight() {
        return delegate.estimateCount(FairnessHierarchy.ROOT);
    }
}
