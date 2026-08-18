package org.synanton.equalix.adapter.out.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.synanton.equalix.config.properties.CmsProperties;
import org.synanton.equalix.config.properties.QueueProperties;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("unchecked")
class RedisCMSAdapterTest {

    private static final String HASH_KEY = "test:cms";
    private static final String TOTAL_KEY = "test:cms:total";

    @Mock
    StringRedisTemplate redisTemplate;
    @Mock
    HashOperations<String, Object, Object> hashOps;
    @Mock
    ValueOperations<String, String> valueOps;

    RedisCMSAdapter adapter;

    @BeforeEach
    void setUp() {
        QueueProperties props = new QueueProperties();
        CmsProperties cms = new CmsProperties();
        cms.setWidth(1024);
        cms.setDepth(3);
        cms.setMode("redis");
        CmsProperties.RedisProperties redis = new CmsProperties.RedisProperties();
        redis.setKeyNamespace(HASH_KEY);
        redis.setFallbackToLocal(false);
        cms.setRedis(redis);
        props.setCms(cms);

        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        adapter = new RedisCMSAdapter(props, redisTemplate);
    }

    // --- estimateCount ---

    @Test
    void shouldReturnZeroForUnknownKey() {
        when(hashOps.multiGet(eq(HASH_KEY), anyList()))
            .thenReturn(Arrays.asList(null, null, null));

        assertThat(adapter.estimateCount("unknown")).isEqualTo(0L);
    }

    @Test
    void shouldReturnMinimumAcrossRows() {
        when(hashOps.multiGet(eq(HASH_KEY), anyList()))
            .thenReturn(Arrays.asList("5", "3", "7"));

        assertThat(adapter.estimateCount("tenant-a")).isEqualTo(3L);
    }

    @Test
    void shouldReturnZeroWhenAllCellsNegative() {
        when(hashOps.multiGet(eq(HASH_KEY), anyList()))
            .thenReturn(Arrays.asList("-2", "-1", "-3"));

        assertThat(adapter.estimateCount("tenant-a")).isEqualTo(0L);
    }

    @Test
    void shouldReturnZeroWhenMinCellIsNegative() {
        when(hashOps.multiGet(eq(HASH_KEY), anyList()))
            .thenReturn(Arrays.asList("4", "-1", "2"));

        assertThat(adapter.estimateCount("tenant-a")).isEqualTo(0L);
    }

    @Test
    void shouldReturnZeroWhenSomeCellsNull() {
        when(hashOps.multiGet(eq(HASH_KEY), anyList()))
            .thenReturn(Arrays.asList(null, "5", null));

        // null treated as 0, min is 0
        assertThat(adapter.estimateCount("tenant-a")).isEqualTo(0L);
    }

    // --- add ---

    @Test
    void shouldExecuteLuaScriptWithCorrectArgCount() {
        adapter.add("tenant-a", 1L);

        // depth=3: ARGV = [delta, field_r0, field_r1, field_r2] - 4 vararg strings
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(HASH_KEY, TOTAL_KEY)),
            anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldExecuteLuaScriptOnDecrement() {
        adapter.add("tenant-a", -1L);

        // negative delta is forwarded to Redis (Redis handles HINCRBY with negative values)
        verify(redisTemplate).execute(any(RedisScript.class), anyList(),
            anyString(), anyString(), anyString(), anyString());
    }

    // --- totalInFlight ---

    @Test
    void shouldReturnTotalInFlight() {
        when(valueOps.get(TOTAL_KEY)).thenReturn("42");

        assertThat(adapter.totalInFlight()).isEqualTo(42L);
    }

    @Test
    void shouldReturnZeroWhenTotalKeyAbsent() {
        when(valueOps.get(TOTAL_KEY)).thenReturn(null);

        assertThat(adapter.totalInFlight()).isEqualTo(0L);
    }

    @Test
    void shouldReturnZeroWhenTotalNegative() {
        // Negative total can theoretically occur from missed increments after a Redis restart
        when(valueOps.get(TOTAL_KEY)).thenReturn("-5");

        assertThat(adapter.totalInFlight()).isEqualTo(0L);
    }

    // --- rebuild ---

    @Test
    void shouldDeleteHashAndRepopulateOnRebuild() {
        adapter.rebuild(Map.of("tenant-a", 3, "tenant-b", 1));

        verify(redisTemplate).delete(HASH_KEY);
        verify(redisTemplate).executePipelined(any(SessionCallback.class));
        verify(valueOps).set(eq(TOTAL_KEY), anyString());
    }

    @Test
    void shouldSetCorrectTotalOnRebuild() {
        adapter.rebuild(Map.of("tenant-a", 3, "tenant-b", 2));

        // total = 3 + 2 = 5
        verify(valueOps).set(TOTAL_KEY, "5");
    }

    @Test
    void shouldDeleteHashButSkipPipelineForEmptySnapshot() {
        adapter.rebuild(Map.of());

        verify(redisTemplate).delete(HASH_KEY);
        verify(valueOps).set(TOTAL_KEY, "0");
        // pipeline should not be called for empty snapshot
        verify(redisTemplate, org.mockito.Mockito.never()).executePipelined(any(SessionCallback.class));
    }

    // --- fallback ---

    @Test
    void shouldReturnZeroOnEstimateCountRedisFailureWithNoFallback() {
        when(hashOps.multiGet(anyString(), anyList()))
            .thenThrow(new org.springframework.dao.DataAccessException("Redis down") {});

        assertThat(adapter.estimateCount("tenant-a")).isEqualTo(0L);
    }

    @Test
    void shouldNotThrowOnAddRedisFailureWithNoFallback() {
        doAnswer(inv -> { throw new RuntimeException("Redis down"); })
            .when(redisTemplate).execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString());

        // must not propagate the exception
        adapter.add("tenant-a", 1L);
    }
}
