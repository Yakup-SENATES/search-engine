package com.example.searchengine.application.search;

import com.example.searchengine.domain.content.*;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.mockito.Mockito;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import com.example.searchengine.infrastructure.cache.SearchCacheKeyGenerator;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based test for cache semantics of the SearchService.
 *
 * <p><b>Validates: Requirements 11.4, 12.1, 12.3, 12.4, 12.5, 12.6, 12.7</b></p>
 *
 * <p>For any sequence of Search_Service invocations with cache enabled:
 * (a) two consecutive calls with the same (q, type, sort, page, limit) 5-tuple produce
 * identical results and the second invocation does not call the Content_Repository;
 * (b) after a successful Content_Aggregator sync run, every previously cached search entry
 * is absent on the next read;
 * (c) when cache.search.enabled=false, every call invokes the repository regardless of repetition;
 * (d) when the underlying call throws or the cache backend is unavailable, no entry is written.</p>
 */
class SearchServiceCachePropertyTest {

    private ContentRepository repository;
    private CacheManager cacheManager;
    private SearchService searchService;

    @BeforeProperty
    void setUp() {
        repository = mock(ContentRepository.class);
        cacheManager = new ConcurrentMapCacheManager("search");

        // Build a Spring context with caching enabled to get proper @Cacheable proxying
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean("contentRepository", ContentRepository.class, () -> repository);
        ctx.registerBean("searchCacheKeyGenerator", KeyGenerator.class, SearchCacheKeyGenerator::new);
        ctx.registerBean("cacheManager", CacheManager.class, () -> cacheManager);
        ctx.register(CacheTestConfig.class);
        ctx.register(DefaultSearchService.class);
        ctx.refresh();

        searchService = ctx.getBean(SearchService.class);
    }

    @Configuration
    @EnableCaching
    static class CacheTestConfig {
    }

    // ─── Property (a): Repeat queries hit cache ───────────────────────────────

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 9: Cache semantics — repeat queries hit cache")
    void repeatQueriesHitCache(@ForAll("searchQueries") SearchQuery query) {
        // Arrange: repository returns a deterministic result
        SearchPage page = buildSearchPage(query);
        when(repository.search(any(SearchCriteria.class))).thenReturn(page);

        // Act: call twice with the same query
        SearchResult first = searchService.search(query);
        SearchResult second = searchService.search(query);

        // Assert: results are identical
        assertThat(second).isEqualTo(first);

        // Assert: repository was called exactly once (second call hit cache)
        verify(repository, times(1)).search(any(SearchCriteria.class));

        // Clean up for next iteration
        cacheManager.getCache("search").clear();
        Mockito.reset(repository);
    }

    // ─── Property (b): Post-sync eviction clears entries ──────────────────────

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 9: Cache semantics — post-sync eviction clears entries")
    void postSyncEvictionClearsEntries(@ForAll("searchQueries") SearchQuery query) {
        // Arrange: repository returns a result
        SearchPage page = buildSearchPage(query);
        when(repository.search(any(SearchCriteria.class))).thenReturn(page);

        // Act: call to populate cache
        searchService.search(query);
        verify(repository, times(1)).search(any(SearchCriteria.class));

        // Simulate sync eviction: clear the "search" cache (as @CacheEvict does)
        cacheManager.getCache("search").clear();

        // Arrange: repository returns a different result after sync
        SearchPage updatedPage = new SearchPage(List.of(), 0);
        when(repository.search(any(SearchCriteria.class))).thenReturn(updatedPage);

        // Act: call again after eviction
        SearchResult afterEviction = searchService.search(query);

        // Assert: repository was called again (cache was evicted)
        verify(repository, times(2)).search(any(SearchCriteria.class));

        // Assert: result reflects the new repository state
        assertThat(afterEviction.items()).isEmpty();
        assertThat(afterEviction.total()).isEqualTo(0);

        // Clean up
        cacheManager.getCache("search").clear();
        Mockito.reset(repository);
    }

    // ─── Property (c): Cache disabled always invokes repository ───────────────

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 9: Cache semantics — cache disabled always invokes repository")
    void cacheDisabledAlwaysInvokesRepository(@ForAll("searchQueries") SearchQuery query) {
        // Arrange: use a no-op search service (no caching) to simulate cache.search.enabled=false
        // When cache is disabled, Spring uses NoOpCacheManager, so @Cacheable is a no-op.
        // We simulate this by creating a non-proxied service directly.
        ContentRepository directRepo = mock(ContentRepository.class);
        SearchPage page = buildSearchPage(query);
        when(directRepo.search(any(SearchCriteria.class))).thenReturn(page);

        DefaultSearchService noCacheService = new DefaultSearchService(directRepo);

        // Act: call twice
        noCacheService.search(query);
        noCacheService.search(query);

        // Assert: repository was called twice (no caching)
        verify(directRepo, times(2)).search(any(SearchCriteria.class));
    }

    // ─── Property (d): Exceptions never persist a cache entry ─────────────────

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 9: Cache semantics — exceptions never persist cache entry")
    void exceptionsNeverPersistCacheEntry(@ForAll("searchQueries") SearchQuery query) {
        // Arrange: repository throws on first call
        when(repository.search(any(SearchCriteria.class)))
                .thenThrow(new ContentRepositoryException("DB down", new RuntimeException("connection refused")));

        // Act: first call throws
        try {
            searchService.search(query);
        } catch (ContentRepositoryException e) {
            // expected
        }

        // Assert: no cache entry was written
        var cache = cacheManager.getCache("search");
        SearchCacheKeyGenerator keyGen = new SearchCacheKeyGenerator();
        Object key = keyGen.generate(searchService, null, query);
        assertThat(cache.get(key)).isNull();

        // Arrange: now repository succeeds
        SearchPage page = buildSearchPage(query);
        Mockito.reset(repository);
        when(repository.search(any(SearchCriteria.class))).thenReturn(page);

        // Act: second call succeeds and should invoke repository (not cached from failed call)
        SearchResult result = searchService.search(query);
        assertThat(result).isNotNull();
        verify(repository, times(1)).search(any(SearchCriteria.class));

        // Clean up
        cacheManager.getCache("search").clear();
        Mockito.reset(repository);
    }

    // ─── Generators ───────────────────────────────────────────────────────────

    @Provide
    Arbitrary<SearchQuery> searchQueries() {
        Arbitrary<String> q = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(50);

        Arbitrary<String> type = Arbitraries.of("video", "text", null);

        Arbitrary<String> sort = Arbitraries.of("score", "popularity", "relevance", null);

        Arbitrary<Integer> page = Arbitraries.integers().between(1, 10);

        Arbitrary<Integer> limit = Arbitraries.integers().between(1, 100);

        return Combinators.combine(q, type, sort, page, limit)
                .as(SearchQuery::new);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private SearchPage buildSearchPage(SearchQuery query) {
        Content content = new Content(
                UUID.randomUUID(),
                "test-provider",
                "ext-" + UUID.randomUUID().toString().substring(0, 8),
                "Title for " + (query.q() != null ? query.q().substring(0, Math.min(query.q().length(), 10)) : "null"),
                "Description",
                ContentType.VIDEO,
                1000L,
                100L,
                0,
                0L,
                "PT5M",
                List.of("tag1"),
                Instant.now().minusSeconds(86400),
                42.5,
                30.0,
                0.0,
                Instant.now(),
                Instant.now()
        );
        return new SearchPage(List.of(content), 1);
    }
}
