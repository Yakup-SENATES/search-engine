package com.example.searchengine.infrastructure.cache;

import com.example.searchengine.application.search.SearchQuery;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Custom cache key generator for the "search" cache region.
 *
 * <p>Generates a stable string key from the {@link SearchQuery} fields:
 * {@code q|type|sort|page|limit}, substituting {@code _NONE_} for any
 * null value. This ensures that the cache key is deterministic and
 * human-readable for debugging purposes.</p>
 *
 * <p>REQ 12.1</p>
 */
@Component("searchCacheKeyGenerator")
public class SearchCacheKeyGenerator implements KeyGenerator {

    private static final String SEPARATOR = "|";
    private static final String NULL_PLACEHOLDER = "_NONE_";

    @Override
    public Object generate(Object target, Method method, Object... params) {
        if (params.length == 0 || !(params[0] instanceof SearchQuery query)) {
            // Fallback: concatenate all params as strings
            StringBuilder sb = new StringBuilder();
            for (Object param : params) {
                if (!sb.isEmpty()) {
                    sb.append(SEPARATOR);
                }
                sb.append(param == null ? NULL_PLACEHOLDER : param.toString());
            }
            return sb.toString();
        }

        return buildKey(query);
    }

    /**
     * Builds the cache key string from the given search query.
     *
     * @param query the search query
     * @return a stable string key in the format "q|type|sort|page|limit"
     */
    private String buildKey(SearchQuery query) {
        return nullSafe(query.q())
                + SEPARATOR + nullSafe(query.type())
                + SEPARATOR + nullSafe(query.sort())
                + SEPARATOR + query.page()
                + SEPARATOR + query.limit();
    }

    private String nullSafe(String value) {
        return value == null ? NULL_PLACEHOLDER : value;
    }
}
