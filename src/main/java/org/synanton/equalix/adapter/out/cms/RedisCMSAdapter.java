package org.synanton.equalix.adapter.out.cms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.synanton.equalix.config.properties.CmsProperties;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.port.out.CMSProviderPort;

/**
 * CMSProviderPort backed by a shared Redis hash matrix.
 * All instances read and write the same sketch, providing a consistent global in-flight view.
 *
 * Storage layout: one Redis hash at {@code keyNamespace}, fields named {@code r{row}:c{col}}.
 * A separate key {@code keyNamespace:total} tracks the net sum of all add() deltas.
 *
 * add() uses a Lua script so all d cell increments and the total counter update are atomic
 * within a single Redis round-trip - no application-side locking required.
 */
@Slf4j
public class RedisCMSAdapter implements CMSProviderPort {

    // KEYS[1]=cms hash key, KEYS[2]=total counter key
    // ARGV[1]=delta, ARGV[2..depth+1]=field names for each row
    private static final RedisScript<Long> ADD_SCRIPT = RedisScript.of("""
            local hash      = KEYS[1]
            local total_key = KEYS[2]
            local delta     = tonumber(ARGV[1])
            for i = 2, #ARGV do
                redis.call('HINCRBY', hash, ARGV[i], delta)
            end
            redis.call('INCRBY', total_key, delta)
            return 1
            """, Long.class);

    // One seed per supported row to keep hash distributions independent.
    private static final long[] ROW_SEEDS = {
        0xDEADBEEFDEADBEEFL, 0xCAFEBABECAFEBABEL,
        0x0102030405060708L, 0xF0E0D0C0B0A09080L,
        0x123456789ABCDEF0L, 0xAABBCCDD11223344L,
        0x5566778899AABBCCL, 0x1A2B3C4D5E6F7A8BL
    };

    private final int width;
    private final int depth;
    private final String hashKey;
    private final String totalKey;
    private final boolean fallbackToLocal;
    private final StringRedisTemplate redisTemplate;
    /** Warm fallback - only non-null when fallbackToLocal=true. */
    private final CountMinSketchAdapter localFallback;

    public RedisCMSAdapter(QueueProperties props, StringRedisTemplate redisTemplate) {
        CmsProperties cms = props.getCms();
        this.width = cms.getWidth();
        this.depth = cms.getDepth();
        this.hashKey = cms.getRedis().getKeyNamespace();
        this.totalKey = hashKey + ":total";
        this.fallbackToLocal = cms.getRedis().isFallbackToLocal();
        this.redisTemplate = redisTemplate;
        this.localFallback = fallbackToLocal ? new CountMinSketchAdapter(props) : null;
        log.info("RedisCMSAdapter initialized: hashKey={}, width={}, depth={}, fallback={}",
            hashKey, width, depth, fallbackToLocal);
    }

    @Override
    public void add(String key, long delta) {
        try {
            List<String> argv = new ArrayList<>(1 + depth);
            argv.add(String.valueOf(delta));
            for (int row = 0; row < depth; row++) {
                argv.add(fieldName(row, hashCell(key, row)));
            }
            redisTemplate.execute(ADD_SCRIPT, List.of(hashKey, totalKey), argv.toArray(new String[0]));
        } catch (Exception e) {
            log.warn("Redis CMS add failed for key='{}': {}", key, e.getMessage());
            if (fallbackToLocal) {
                localFallback.add(key, delta);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public long estimateCount(String key) {
        try {
            List<Object> fields = new ArrayList<>(depth);
            for (int row = 0; row < depth; row++) {
                fields.add(fieldName(row, hashCell(key, row)));
            }
            List<Object> values = redisTemplate.opsForHash().multiGet(hashKey, fields);
            long min = Long.MAX_VALUE;
            for (Object val : values) {
                long v = val == null ? 0L : Long.parseLong((String) val);
                if (v < min) {
                    min = v;
                }
            }
            return Math.max(0L, min == Long.MAX_VALUE ? 0L : min);
        } catch (Exception e) {
            log.warn("Redis CMS estimateCount failed for key='{}': {}", key, e.getMessage());
            return fallbackToLocal ? localFallback.estimateCount(key) : 0L;
        }
    }

    /**
     * Resets the Redis hash and repopulates it from the snapshot using a pipelined batch.
     * Called by the Watchdog after reconciling client_counts with actual DB state;
     * because the hash is shared, one Watchdog run rebuilds the sketch for all instances at once.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void rebuild(Map<String, Integer> snapshot) {
        try {
            redisTemplate.delete(hashKey);
            if (!snapshot.isEmpty()) {
                redisTemplate.executePipelined(new SessionCallback<Void>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <K, V> Void execute(org.springframework.data.redis.core.RedisOperations<K, V> ops) {
                        org.springframework.data.redis.core.RedisOperations<String, String> strOps =
                            (org.springframework.data.redis.core.RedisOperations<String, String>) ops;
                        snapshot.forEach((fairnessKey, count) -> {
                            for (int row = 0; row < depth; row++) {
                                strOps.opsForHash().increment(
                                    hashKey, fieldName(row, hashCell(fairnessKey, row)), count);
                            }
                        });
                        return null;
                    }
                });
            }
            long total = snapshot.values().stream().mapToLong(Integer::longValue).sum();
            redisTemplate.opsForValue().set(totalKey, String.valueOf(total));
            log.info("Redis CMS rebuilt from {} snapshot entries; total={}", snapshot.size(), total);
        } catch (Exception e) {
            log.warn("Redis CMS rebuild failed: {}", e.getMessage());
            if (fallbackToLocal) {
                localFallback.rebuild(snapshot);
            }
        }
    }

    @Override
    public long totalInFlight() {
        try {
            String val = redisTemplate.opsForValue().get(totalKey);
            return val == null ? 0L : Math.max(0L, Long.parseLong(val));
        } catch (Exception e) {
            log.warn("Redis CMS totalInFlight failed: {}", e.getMessage());
            return fallbackToLocal ? localFallback.totalInFlight() : 0L;
        }
    }

    private String fieldName(int row, int col) {
        return "r" + row + ":c" + col;
    }

    /**
     * 64-bit mixing hash with a per-row seed keeps row distributions statistically independent.
     * The mixing constants are from the finalizer of MurmurHash3 / xxHash.
     */
    private int hashCell(String key, int row) {
        long h = key.hashCode() ^ ROW_SEEDS[row % ROW_SEEDS.length];
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return Math.floorMod(h, width);
    }
}
