package org.synanton.equalix.adapter.out.cms;

import java.nio.charset.StandardCharsets;

/**
 * Maps a fairness key to one cell per sketch row. Shared by the local and Redis sketches so both use the same
 * layout.
 *
 * <p>The key's UTF-8 bytes are hashed to 64 bits (FNV-1a, then a SplitMix64 finalizer). Each row mixes that hash
 * with its own row constant. Two distinct keys therefore share every row only if their 64-bit hashes collide
 * (probability about K²/2⁶⁵ for K keys). Before, rows were derived from the 32-bit {@code String.hashCode()},
 * so keys such as {@code "…Aa"} and {@code "…BB"} collided in every row whatever the sketch size.
 */
final class CmsKeyHasher {

    /** Version of the cell layout; persisted sketches must be rebuilt when it changes. */
    static final int LAYOUT_VERSION = 2;

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;

    private final int width;
    private final int depth;

    CmsKeyHasher(int width, int depth) {
        if (width <= 0 || depth <= 0) {
            throw new IllegalArgumentException("CMS width and depth must be positive: " + width + "x" + depth);
        }
        this.width = width;
        this.depth = depth;
    }

    /** Returns the cell index in each of the {@code depth} rows. */
    int[] cells(String key) {
        long keyHash = hash64(key);
        int[] cells = new int[depth];
        for (int row = 0; row < depth; row++) {
            cells[row] = (int) Math.floorMod(mix(keyHash + (row + 1) * GOLDEN_GAMMA), (long) width);
        }
        return cells;
    }

    static long hash64(String key) {
        long hash = FNV_OFFSET_BASIS;
        for (byte value : key.getBytes(StandardCharsets.UTF_8)) {
            hash ^= value & 0xff;
            hash *= FNV_PRIME;
        }
        return mix(hash);
    }

    /** SplitMix64 finalizer: full avalanche, so nearby inputs map to unrelated outputs. */
    private static long mix(long value) {
        long mixed = value;
        mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        return mixed ^ (mixed >>> 31);
    }
}
