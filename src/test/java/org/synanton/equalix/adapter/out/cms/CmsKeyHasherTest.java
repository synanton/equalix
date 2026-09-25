package org.synanton.equalix.adapter.out.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CmsKeyHasherTest {

    private static final int[] LAYOUT_V2_TENANT_1 = {60959, 59496, 16123, 59780, 12693};

    private final CmsKeyHasher hasher = new CmsKeyHasher(65_536, 5);

    @Test
    void shouldPinTheCellLayout() {
        // Persisted Redis sketches depend on this layout. If this fails, the layout changed: bump
        // CmsKeyHasher.LAYOUT_VERSION so the Redis key changes too, then update the expected cells.
        assertThat(CmsKeyHasher.LAYOUT_VERSION).isEqualTo(2);
        assertThat(hasher.cells("tenant-1")).containsExactly(LAYOUT_V2_TENANT_1);
    }

    @Test
    void shouldBeDeterministicAndInRange() {
        int[] first = hasher.cells("tenant-42");

        assertThat(hasher.cells("tenant-42")).containsExactly(first);
        assertThat(Arrays.stream(first).min().orElseThrow()).isGreaterThanOrEqualTo(0);
        assertThat(Arrays.stream(first).max().orElseThrow()).isLessThan(65_536);
    }

    @Test
    void shouldSeparateKeysWhoseStringHashCodesCollide() {
        assertThat("tenant-Aa".hashCode()).isEqualTo("tenant-BB".hashCode());

        int[] left = hasher.cells("tenant-Aa");
        int[] right = hasher.cells("tenant-BB");

        long sharedRows = IntStream.range(0, left.length).filter(row -> left[row] == right[row]).count();
        assertThat(sharedRows).isZero();
    }

    @Test
    void shouldHashNonAsciiKeysByTheirUtf8Bytes() {
        assertThat(CmsKeyHasher.hash64("mandant-ä")).isNotEqualTo(CmsKeyHasher.hash64("mandant-a"));
    }

    @Test
    void shouldSupportMoreRowsThanTheOldSeedTable() {
        int[] cells = new CmsKeyHasher(1_024, 12).cells("tenant-1");

        assertThat(cells).hasSize(12);
        assertThat(Arrays.stream(cells).distinct().count()).isGreaterThan(1);
    }

    @Test
    void shouldSpreadKeysEvenlyOverColumns() {
        int width = 256;
        int keys = 256_000;
        CmsKeyHasher small = new CmsKeyHasher(width, 1);
        int[] buckets = new int[width];
        for (int key = 0; key < keys; key++) {
            buckets[small.cells("tenant-" + key)[0]]++;
        }

        // Expected 1,000 per bucket; a uniform hash stays well within ±15% (about ±4.7 standard deviations).
        assertThat(Arrays.stream(buckets).min().orElseThrow()).isGreaterThanOrEqualTo(850);
        assertThat(Arrays.stream(buckets).max().orElseThrow()).isLessThanOrEqualTo(1_150);
    }

    @Test
    void shouldRejectNonPositiveDimensions() {
        assertThatThrownBy(() -> new CmsKeyHasher(0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CmsKeyHasher(1_024, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
