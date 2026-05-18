package com.example.searchengine.application.search;

import com.example.searchengine.domain.content.ContentRepository;
import com.example.searchengine.domain.content.ContentType;
import com.example.searchengine.domain.content.SearchCriteria;
import com.example.searchengine.domain.content.SearchPage;
import com.example.searchengine.domain.content.SortField;
import com.example.searchengine.infrastructure.metrics.SearchMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * Default implementation of {@link SearchService}.
 *
 * <p>Converts the application-level {@link SearchQuery} into a domain-level
 * {@link SearchCriteria}, delegates to the {@link ContentRepository}, and
 * wraps the result in a {@link SearchResult}.</p>
 *
 * <p>Caching is performed manually rather than via {@code @Cacheable}: the
 * cache is consulted via {@link CacheManager#getCache(String)} and the key is
 * built by the configured {@code searchCacheKeyGenerator} bean (REQ 12.1,
 * 12.6). The manual approach is here because we need to know <em>at the call
 * site</em> whether the result came from the cache or the repository — that
 * bit is the {@code cache_hit} tag on
 * {@link SearchMetrics#record(Duration, boolean)} (operability quick-wins
 * REQ 1.4) and the increment target for the hit / miss counters (REQ 1.5).</p>
 *
 * <p>Cache backend failure is still tolerated transparently because the
 * {@link CacheManager} bean is wrapped in
 * {@code com.example.searchengine.infrastructure.cache.ResilientCacheManager}
 * (REQ 12.7), which swallows backend exceptions and returns {@code null} on
 * read so we fall through to the repository.</p>
 *
 * <p>REQ 8.2, 8.5, 9.1, 9.3–9.8, 12.1, 12.6, 20.3</p>
 */
@Service
public class DefaultSearchService implements SearchService {

    /** Cache region name; matches {@code CacheConfig.SEARCH_CACHE_NAME}. */
    private static final String SEARCH_CACHE_NAME = "search";

    /** A real {@link Method} reference for {@link KeyGenerator}; harmless and stable. */
    private static final Method SEARCH_METHOD = lookupSearchMethod();

    private final ContentRepository contentRepository;
    private final CacheManager cacheManager;
    private final KeyGenerator searchCacheKeyGenerator;
    private final SearchMetrics searchMetrics;

    public DefaultSearchService(ContentRepository contentRepository,
                                CacheManager cacheManager,
                                @Qualifier("searchCacheKeyGenerator") KeyGenerator searchCacheKeyGenerator,
                                SearchMetrics searchMetrics) {
        this.contentRepository = contentRepository;
        this.cacheManager = cacheManager;
        this.searchCacheKeyGenerator = searchCacheKeyGenerator;
        this.searchMetrics = searchMetrics;
    }

    @Override
    public SearchResult search(SearchQuery query) {
        long startNanos = System.nanoTime();
        Cache cache = cacheManager.getCache(SEARCH_CACHE_NAME);
        Object key = (cache == null) ? null : searchCacheKeyGenerator.generate(this, SEARCH_METHOD, query);

        // Read-through: consult the cache first.
        if (cache != null) {
            Cache.ValueWrapper wrapper = cache.get(key);
            if (wrapper != null) {
                Object cached = wrapper.get();
                if (cached instanceof SearchResult cachedResult) {
                    searchMetrics.recordHit();
                    searchMetrics.record(elapsed(startNanos), true);
                    // Return a copy with cacheHit=true so the controller can record it.
                    return new SearchResult(cachedResult.items(), cachedResult.total(),
                            cachedResult.page(), cachedResult.limit(), true);
                }
            }
            searchMetrics.recordMiss();
        } else {
            // No cache region available (cache disabled or backend down). Treat
            // every call as a miss — the timer's cache_hit=false bucket and the
            // miss counter both track this state.
            searchMetrics.recordMiss();
        }

        SearchResult result = loadFromRepository(query);

        if (cache != null && result != null) {
            cache.put(key, result);
        }
        searchMetrics.record(elapsed(startNanos), false);
        return result;
    }

    @Override
    public SearchResult listTop(String sort, String type, int limit) {
        SortField sortField = SortField.parse(sort);
        ContentType typeFilter = type != null && !type.isBlank()
                ? ContentType.fromProviderValue(type)
                : null;

        SearchPage page = contentRepository.listTop(sortField, typeFilter, limit);

        return new SearchResult(
                page.items(),
                page.total(),
                1,
                limit
        );
    }

    private SearchResult loadFromRepository(SearchQuery query) {
        ContentType type = query.type() != null && !query.type().isBlank()
                ? ContentType.fromProviderValue(query.type())
                : null;

        SortField sort = SortField.parse(query.sort());

        SearchCriteria criteria = new SearchCriteria(
                query.q(),
                type,
                sort,
                query.page(),
                query.limit()
        );

        SearchPage page = contentRepository.search(criteria);

        return new SearchResult(
                page.items(),
                page.total(),
                query.page(),
                query.limit()
        );
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static Method lookupSearchMethod() {
        try {
            return DefaultSearchService.class.getMethod("search", SearchQuery.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("search(SearchQuery) method missing", e);
        }
    }
}
