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

    private static final long[] ROW_SEEDS = {
        0xDEADBEEFDEADBEEFL, 0xCAFEBABECAFEBABEL,
        0x0102030405060708L, 0xF0E0D0C0B0A09080L,
        0x123456789ABCDEF0L, 0xAABBCCDD11223344L,
        0x5566778899AABBCCL, 0x1A2B3C4D5E6F7A8BL
    };

    private final int width;
    private final int depth;
    private long[][] table;
    private long total;

    public CountMinSketchAdapter(QueueProperties properties) {
        this.width = properties.getCms().getWidth();
        this.depth = properties.getCms().getDepth();
        this.table = newTable();
        log.info("Initialized CountMinSketch width={} depth={}", width, depth);
    }

    @Override
    public synchronized void add(String key, long delta) {
        if (delta == 0) {
            return;
        }
        for (int row = 0; row < depth; row++) {
            table[row][hashCell(key, row)] += delta;
        }
        total += delta;
    }

    @Override
    public synchronized long estimateCount(String key) {
        long min = Long.MAX_VALUE;
        for (int row = 0; row < depth; row++) {
            min = Math.min(min, table[row][hashCell(key, row)]);
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

    private int hashCell(String key, int row) {
        long hash = key.hashCode() ^ ROW_SEEDS[row % ROW_SEEDS.length];
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return Math.floorMod(hash, width);
    }
}
