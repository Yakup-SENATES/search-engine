package com.example.searchengine.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * Unit tests for {@link ResilientCacheManager}.
 *
 * <p>Covers REQ 12.7: when the cache backend throws a
 * {@link RedisConnectionFailureException} (or any other {@link RuntimeException}),
 * the wrapper logs WARN and lets the request fall through:
 * reads return {@code null}, writes silently no-op.</p>
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class ResilientCacheManagerTest {

    private CacheManager delegate;
    private Cache delegateCache;
    private ResilientCacheManager manager;

    @BeforeEach
    void setUp() {
        delegate = mock(CacheManager.class);
        delegateCache = mock(Cache.class);
        when(delegateCache.getName()).thenReturn("search");
        when(delegate.getCache("search")).thenReturn(delegateCache);
        manager = new ResilientCacheManager(delegate);
    }

    @Test
    @DisplayName("getCache returns null when delegate throws Redis connection failure")
    void getCacheReturnsNullOnBackendFailure() {
        when(delegate.getCache("search"))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(manager.getCache("search")).isNull();
    }

    @Test
    @DisplayName("getCacheNames returns empty list when delegate fails")
    void getCacheNamesReturnsEmptyOnFailure() {
        when(delegate.getCacheNames()).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(manager.getCacheNames()).isEmpty();
    }

    @Nested
    @DisplayName("when wrapped cache throws on read operations")
    class ReadFailures {

        @Test
        @DisplayName("get(key) returns null instead of propagating")
        void getKeyReturnsNull() {
            when(delegateCache.get(any())).thenThrow(new RedisConnectionFailureException("down"));

            Cache cache = manager.getCache("search");
            assertThat(cache).isNotNull();
            assertThat(cache.get("any-key")).isNull();
        }

        @Test
        @DisplayName("get(key, type) returns null instead of propagating")
        void getKeyTypeReturnsNull() {
            when(delegateCache.get(any(), any(Class.class)))
                    .thenThrow(new RedisConnectionFailureException("down"));

            Cache cache = manager.getCache("search");
            assertThat(cache).isNotNull();
            assertThat(cache.get("any-key", String.class)).isNull();
        }

        @Test
        @DisplayName("get(key, valueLoader) falls through to the loader on backend failure")
        void getWithLoaderFallsThroughToLoader() {
            when(delegateCache.get(any(), any(java.util.concurrent.Callable.class)))
                    .thenThrow(new RedisConnectionFailureException("down"));

            Cache cache = manager.getCache("search");
            assertThat(cache).isNotNull();

            AtomicInteger calls = new AtomicInteger();
            String result = cache.get("k", () -> {
                calls.incrementAndGet();
                return "loaded";
            });

            assertThat(result).isEqualTo("loaded");
            assertThat(calls).hasValue(1);
        }

        @Test
        @DisplayName("get(key, valueLoader) propagates ValueRetrievalException from the loader itself")
        void valueRetrievalExceptionPropagates() {
            // delegate honors the loader contract: when the loader throws, it wraps the cause
            // in a Cache.ValueRetrievalException.
            when(delegateCache.get(any(), any(java.util.concurrent.Callable.class)))
                    .thenAnswer(inv -> {
                        java.util.concurrent.Callable<?> loader = inv.getArgument(1);
                        try {
                            return loader.call();
                        } catch (Exception e) {
                            throw new Cache.ValueRetrievalException(inv.getArgument(0), loader, e);
                        }
                    });

            Cache cache = manager.getCache("search");
            assertThat(cache).isNotNull();

            assertThatThrownBy(() -> cache.get("k", () -> {
                throw new IllegalStateException("loader failed");
            })).isInstanceOf(Cache.ValueRetrievalException.class);
        }
    }

    @Nested
    @DisplayName("when wrapped cache throws on write operations")
    class WriteFailures {

        @Test
        @DisplayName("put silently no-ops on backend failure")
        void putSilentlyNoOps() {
            doThrow(new RedisConnectionFailureException("down"))
                    .when(delegateCache).put(any(), any());

            Cache cache = manager.getCache("search");
            assertThat(cache).isNotNull();

            assertThatCode(() -> cache.put("k", "v")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("putIfAbsent returns null on backend failure")
        void putIfAbsentReturnsNull() {
            when(delegateCache.putIfAbsent(any(), any()))
                    .thenThrow(new RedisConnectionFailureException("down"));

            Cache cache = manager.getCache("search");
            assertThat(cache).isNotNull();
            assertThat(cache.putIfAbsent("k", "v")).isNull();
        }

        @Test
        @DisplayName("evict silently no-ops on backend failure")
        void evictSilentlyNoOps() {
            doThrow(new RedisConnectionFailureException("down"))
                    .when(delegateCache).evict(any());

            Cache cache = manager.getCache("search");
            assertThat(cache).isNotNull();

            assertThatCode(() -> cache.evict("k")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("evictIfPresent returns false on backend failure")
        void evictIfPresentReturnsFalse() {
            when(delegateCache.evictIfPresent(any()))
                    .thenThrow(new RedisConnectionFailureException("down"));

            Cache cache = manager.getCache("search");
            assertThat(cache).isNotNull();
            assertThat(cache.evictIfPresent("k")).isFalse();
        }

        @Test
        @DisplayName("clear silently no-ops on backend failure")
        void clearSilentlyNoOps() {
            doThrow(new RedisConnectionFailureException("down"))
                    .when(delegateCache).clear();

            Cache cache = manager.getCache("search");
            assertThat(cache).isNotNull();
            assertThatCode(cache::clear).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("invalidate returns false on backend failure")
        void invalidateReturnsFalse() {
            when(delegateCache.invalidate())
                    .thenThrow(new RedisConnectionFailureException("down"));

            Cache cache = manager.getCache("search");
            assertThat(cache).isNotNull();
            assertThat(cache.invalidate()).isFalse();
        }
    }

    @Test
    @DisplayName("happy path delegates to underlying cache")
    void happyPathDelegates() {
        when(delegateCache.get("k")).thenReturn(new SimpleValueWrapper("hello"));
        doNothing().when(delegateCache).put("k", "hello");

        Cache cache = manager.getCache("search");
        assertThat(cache).isNotNull();

        cache.put("k", "hello");
        Cache.ValueWrapper wrapper = cache.get("k");

        assertThat(wrapper).isNotNull();
        assertThat(wrapper.get()).isEqualTo("hello");
        assertThat(cache.getName()).isEqualTo("search");
        verify(delegateCache, times(1)).put("k", "hello");
    }
}
