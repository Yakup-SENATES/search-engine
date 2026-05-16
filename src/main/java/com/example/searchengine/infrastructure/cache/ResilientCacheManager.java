package com.example.searchengine.infrastructure.cache;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.lang.Nullable;

/**
 * {@link CacheManager} decorator that gracefully tolerates cache backend failures.
 *
 * <p>Wraps another {@link CacheManager} (typically a {@code RedisCacheManager}) and
 * intercepts every cache operation. If the backend throws — most commonly a
 * {@link RedisConnectionFailureException} when Redis is unreachable — the failure is
 * caught, logged at WARN, and the request is allowed to proceed:
 *
 * <ul>
 *   <li>On read failures, {@link Cache#get(Object)} returns {@code null}, causing
 *       Spring Cache to invoke the underlying method and fall through to the
 *       repository.</li>
 *   <li>On write/evict/clear failures, the operation silently no-ops.</li>
 * </ul>
 *
 * <p>This implements REQ 12.7: when the cache backend is unavailable, the system
 * continues to serve requests (with degraded latency) rather than fail.</p>
 */
public class ResilientCacheManager implements CacheManager {

    private static final Logger log = LoggerFactory.getLogger(ResilientCacheManager.class);

    private final CacheManager delegate;

    public ResilientCacheManager(CacheManager delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cache manager must not be null");
    }

    @Override
    @Nullable
    public Cache getCache(String name) {
        try {
            Cache cache = delegate.getCache(name);
            return cache == null ? null : new ResilientCache(cache);
        } catch (RuntimeException ex) {
            log.warn("Cache backend unavailable while resolving cache '{}': {}", name, ex.getMessage());
            return null;
        }
    }

    @Override
    public Collection<String> getCacheNames() {
        try {
            return delegate.getCacheNames();
        } catch (RuntimeException ex) {
            log.warn("Cache backend unavailable while resolving cache names: {}", ex.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * {@link Cache} decorator that swallows backend exceptions on every operation.
     */
    static final class ResilientCache implements Cache {

        private final Cache delegate;

        ResilientCache(Cache delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Object getNativeCache() {
            return delegate.getNativeCache();
        }

        @Override
        @Nullable
        public ValueWrapper get(Object key) {
            try {
                return delegate.get(key);
            } catch (RuntimeException ex) {
                handleReadFailure("get", key, ex);
                return null;
            }
        }

        @Override
        @Nullable
        public <T> T get(Object key, @Nullable Class<T> type) {
            try {
                return delegate.get(key, type);
            } catch (RuntimeException ex) {
                handleReadFailure("get", key, ex);
                return null;
            }
        }

        @Override
        @Nullable
        public <T> T get(Object key, Callable<T> valueLoader) {
            try {
                return delegate.get(key, valueLoader);
            } catch (Cache.ValueRetrievalException ex) {
                // The loader itself threw — propagate so Spring/the caller can react.
                throw ex;
            } catch (RuntimeException ex) {
                // Backend failure: fall through by invoking the loader directly.
                handleReadFailure("get-with-loader", key, ex);
                try {
                    return valueLoader.call();
                } catch (Exception loaderEx) {
                    throw new Cache.ValueRetrievalException(key, valueLoader, loaderEx);
                }
            }
        }

        @Override
        public void put(Object key, @Nullable Object value) {
            try {
                delegate.put(key, value);
            } catch (RuntimeException ex) {
                handleWriteFailure("put", key, ex);
            }
        }

        @Override
        @Nullable
        public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
            try {
                return delegate.putIfAbsent(key, value);
            } catch (RuntimeException ex) {
                handleWriteFailure("putIfAbsent", key, ex);
                return null;
            }
        }

        @Override
        public void evict(Object key) {
            try {
                delegate.evict(key);
            } catch (RuntimeException ex) {
                handleWriteFailure("evict", key, ex);
            }
        }

        @Override
        public boolean evictIfPresent(Object key) {
            try {
                return delegate.evictIfPresent(key);
            } catch (RuntimeException ex) {
                handleWriteFailure("evictIfPresent", key, ex);
                return false;
            }
        }

        @Override
        public void clear() {
            try {
                delegate.clear();
            } catch (RuntimeException ex) {
                handleWriteFailure("clear", null, ex);
            }
        }

        @Override
        public boolean invalidate() {
            try {
                return delegate.invalidate();
            } catch (RuntimeException ex) {
                handleWriteFailure("invalidate", null, ex);
                return false;
            }
        }

        private void handleReadFailure(String operation, @Nullable Object key, RuntimeException ex) {
            log.warn(
                    "Cache backend unavailable on {} for cache '{}' key='{}': {}; falling through to repository",
                    operation, delegate.getName(), key, ex.getMessage());
        }

        private void handleWriteFailure(String operation, @Nullable Object key, RuntimeException ex) {
            log.warn(
                    "Cache backend unavailable on {} for cache '{}' key='{}': {}; ignoring",
                    operation, delegate.getName(), key, ex.getMessage());
        }
    }
}
