package com.example.searchengine.application.search;

import com.example.searchengine.domain.content.ContentRepository;
import com.example.searchengine.domain.content.ContentType;
import com.example.searchengine.domain.content.SearchCriteria;
import com.example.searchengine.domain.content.SearchPage;
import com.example.searchengine.domain.content.SortField;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link SearchService}.
 *
 * <p>Converts the application-level {@link SearchQuery} into a domain-level
 * {@link SearchCriteria}, delegates to the {@link ContentRepository}, and
 * wraps the result in a {@link SearchResult}.</p>
 *
 * <p>The {@code @Cacheable} annotation caches successful (non-null) results
 * in the "search" cache region, keyed by the custom
 * {@code searchCacheKeyGenerator} (REQ 12.1, 12.6).</p>
 *
 * <p>REQ 8.2, 8.5, 9.1, 9.3–9.8, 12.1, 12.6, 20.3</p>
 */
@Service
public class DefaultSearchService implements SearchService {

    private final ContentRepository contentRepository;

    public DefaultSearchService(ContentRepository contentRepository) {
        this.contentRepository = contentRepository;
    }

    @Override
    @Cacheable(cacheNames = "search", keyGenerator = "searchCacheKeyGenerator", unless = "#result == null")
    public SearchResult search(SearchQuery query) {
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
}
