package com.example.searchengine.infrastructure.provider.jsonprovider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Jackson record representing the top-level JSON provider response.
 *
 * <p>Schema (REQ 2.2): {@code { "contents": [...] }}</p>
 *
 * @param contents list of content items from the provider
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonProviderResponse(
        List<JsonContentDto> contents
) {
}
