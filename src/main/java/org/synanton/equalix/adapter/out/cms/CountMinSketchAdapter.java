package org.synanton.equalix.adapter.out.cms;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.port.out.CMSProviderPort;

/**
 * In-process Count-Min Sketch that supports positive and negative deltas.
 * All public methods are synchronized on this instance.
 * Estimates may over-count due to hash collisions; estimateCount always returns max(0, estimate).
 */
@Slf4j
public class CountMinSketchAdapter implements CMSProviderPort {

    private final int width;
    private final int depth;
    private final CmsKeyHasher hasher;
    private long[][] table;
    private long total;

    public CountMinSketchAdapter(QueueProperties properties) {
        this.width = properties.getCms().getWidth();
        this.depth = properties.getCms().getDepth();
        this.hasher = new CmsKeyHasher(width, depth);
        this.table = newTable();
        log.info("Initialized CountMinSketch width={} depth={}", width, depth);
    }

    @Override
    public synchronized void add(String key, long delta) {
        if (delta == 0) {
            return;
        }
        int[] cells = hasher.cells(key);
        for (int row = 0; row < depth; row++) {
            table[row][cells[row]] += delta;
        }
        total += delta;
    }

    @Override
    public synchronized long estimateCount(String key) {
        int[] cells = hasher.cells(key);
        long min = Long.MAX_VALUE;
        for (int row = 0; row < depth; row++) {
            min = Math.min(min, table[row][cells[row]]);
        }
        return Math.max(0L, min == Long.MAX_VALUE ? 0L : min);
    }

    @Override
    public synchronized void rebuild(Map<String, Integer> snapshot) {
        this.table = newTable();
        this.total = 0L;
        snapshot.forEach((key, count) -> add(key, count));
        log.info("CMS rebuilt from {} entries", snapshot.size());
    }

    @Override
    public synchronized long totalInFlight() {
        return Math.max(0L, total);
    }

    private long[][] newTable() {
        return new long[depth][width];
    }
}
