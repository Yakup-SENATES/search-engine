package com.example.searchengine.infrastructure.cache;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Spring Cache configuration for the {@code search} cache region.
 *
 * <p>Wires either a {@link RedisCacheManager} (wrapped in {@link ResilientCacheManager}
 * for transparent fall-through when the backend is unavailable — REQ 12.7) or a
 * {@link NoOpCacheManager} (when {@code cache.search.enabled=false} — REQ 12.5).</p>
 *
 * <p>The TTL applied to entries in the {@code search} region is bound to
 * {@code cache.search.ttl-seconds} (default 300 s, REQ 12.2). {@code @EnableCaching}
 * is always active, so {@code @Cacheable} on {@code DefaultSearchService} resolves
 * against whichever manager is exposed.</p>
 *
 * @see CacheProperties
 * @see ResilientCacheManager
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    /** Single cache region used by {@code DefaultSearchService} (REQ 12.1). */
    public static final String SEARCH_CACHE_NAME = "search";

    /**
     * Builds a Redis-backed {@link CacheManager} wrapped in {@link ResilientCacheManager}
     * so a Redis outage degrades to repository-only reads (REQ 12.7) instead of failing.
     *
     * <p>Only loaded when {@code cache.search.enabled=true} (default). When disabled,
     * {@link #noOpCacheManager()} is exposed instead (REQ 12.5).</p>
     */
    @Bean("cacheManager")
    @ConditionalOnProperty(name = "cache.search.enabled", havingValue = "true", matchIfMissing = true)
    public CacheManager redisCacheManager(
            RedisConnectionFactory connectionFactory,
            CacheProperties cacheProperties) {

        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(cacheProperties.getTtlSeconds()))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer()));

        RedisCacheManager redisCacheManager = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(configuration)
                .initialCacheNames(java.util.Set.of(SEARCH_CACHE_NAME))
                .build();

        return new ResilientCacheManager(redisCacheManager);
    }

    /**
     * Exposes a {@link NoOpCacheManager} when caching is disabled so {@code @Cacheable}
     * becomes a transparent no-op (REQ 12.5). With this in place the application can
     * start even if Redis is not configured or unreachable.
     */
    @Bean("cacheManager")
    @ConditionalOnProperty(name = "cache.search.enabled", havingValue = "false")
    public CacheManager noOpCacheManager() {
        return new NoOpCacheManager();
    }
}
