package com.example.searchengine.web.openapi;

import com.example.searchengine.web.api.SearchController;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;

/**
 * SpringDoc OpenAPI configuration for the Search Engine Aggregator service.
 *
 * <p>Registers the top-level {@link OpenAPI} bean with API metadata so SpringDoc
 * publishes <code>/v3/api-docs</code> and <code>/swagger-ui.html</code>
 * (REQ 15.1, 15.2). An {@link OperationCustomizer} bean enriches the search
 * operation programmatically — keeping the OpenAPI concerns out of
 * {@link SearchController} — by attaching three named examples that illustrate
 * distinct behaviours (REQ 15.4):</p>
 *
 * <ul>
 *   <li><strong>keyword-only</strong>: <code>?q=java</code> with the default
 *       <code>sort=score, page=1, limit=10</code>.</li>
 *   <li><strong>type-filtered</strong>: <code>?q=microservices&amp;type=text</code>.</li>
 *   <li><strong>paginated</strong>: <code>?q=java&amp;page=3&amp;limit=25</code>.</li>
 * </ul>
 *
 * <p>The 400 response references the {@code ErrorResponse} schema registered in
 * {@link Components}, whose shape matches the
 * {@code GlobalExceptionHandler} error envelope exactly (REQ 14.1, 14.2, 15.3):
 * <code>{"error":{"code":"INVALID_QUERY","message":"..."}}</code>.</p>
 *
 * <p>REQ 15.1, 15.2, 15.3, 15.4</p>
 */
@Configuration
public class OpenApiConfig {

    /** Schema name used both in {@link Components} and in {@code $ref} pointers. */
    public static final String ERROR_RESPONSE_SCHEMA = "ErrorResponse";

    static final String EXAMPLE_KEYWORD_ONLY = "keyword-only";
    static final String EXAMPLE_TYPE_FILTERED = "type-filtered";
    static final String EXAMPLE_PAGINATED = "paginated";

    private static final String JSON_MEDIA_TYPE = "application/json";
    private static final String STATUS_OK = "200";
    private static final String STATUS_BAD_REQUEST = "400";

    /**
     * Top-level OpenAPI bean carrying API metadata and the shared
     * {@code ErrorResponse} schema (REQ 15.1, 15.2, 15.3).
     *
     * @return the customized {@link OpenAPI} document descriptor
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Search Engine Aggregator API")
                        .version("v1")
                        .description("""
                                Aggregates content from multiple providers (JSON and XML),
                                normalizes and scores it, and exposes a paginated, sortable,
                                keyword-searchable REST API plus a Thymeleaf dashboard.
                                """)
                        .license(new License().name("Apache 2.0")))
                .components(new Components()
                        .addSchemas(ERROR_RESPONSE_SCHEMA, buildErrorResponseSchema()));
    }

    /**
     * Programmatic customizer that decorates the {@code GET /api/v1/search}
     * operation with three named examples and the {@code ErrorResponse}-shaped
     * 400 response (REQ 15.3, 15.4).
     *
     * @return an {@link OperationCustomizer} bean picked up by SpringDoc
     */
    @Bean
    public OperationCustomizer searchOperationCustomizer() {
        return (operation, handlerMethod) -> {
            if (!isSearchEndpoint(handlerMethod)) {
                return operation;
            }
            decorateQueryParameterExamples(operation);
            decorateSuccessResponseExamples(operation);
            decorateValidationErrorResponse(operation);
            return operation;
        };
    }

    private static boolean isSearchEndpoint(HandlerMethod handlerMethod) {
        return SearchController.class.equals(handlerMethod.getBeanType())
                && "search".equals(handlerMethod.getMethod().getName());
    }

    private static Schema<?> buildErrorResponseSchema() {
        Schema<?> errorBody = new ObjectSchema()
                .description("Inner error payload (REQ 14.1, 14.2)")
                .addProperty("code", new StringSchema()
                        .description("Stable machine-readable error code emitted by GlobalExceptionHandler "
                                + "(e.g. INVALID_QUERY, PAYLOAD_TOO_LARGE, RATE_LIMITED, "
                                + "DATABASE_UNAVAILABLE, PROVIDER_ERROR, INTERNAL_ERROR).")
                        .example("INVALID_QUERY"))
                .addProperty("message", new StringSchema()
                        .description("Human-readable, sanitized error message (REQ 14, 18.5).")
                        .example("q must be 1..200 chars"))
                .addRequiredItem("code")
                .addRequiredItem("message");

        return new ObjectSchema()
                .description("Standard error envelope returned by GlobalExceptionHandler (REQ 14.1, 15.3).")
                .addProperty("error", errorBody)
                .addRequiredItem("error");
    }

    private static void decorateQueryParameterExamples(Operation operation) {
        if (operation.getParameters() == null) {
            return;
        }
        for (Parameter parameter : operation.getParameters()) {
            String name = parameter.getName();
            if (name == null) {
                continue;
            }
            switch (name) {
                case "q" -> {
                    parameter.addExample(EXAMPLE_KEYWORD_ONLY, new Example()
                            .summary("Keyword-only search")
                            .description("?q=java — uses default sort=score, page=1, limit=10")
                            .value("java"));
                    parameter.addExample(EXAMPLE_TYPE_FILTERED, new Example()
                            .summary("Type-filtered search")
                            .description("?q=microservices&type=text — restricts to text content")
                            .value("microservices"));
                    parameter.addExample(EXAMPLE_PAGINATED, new Example()
                            .summary("Paginated search")
                            .description("?q=java&page=3&limit=25 — third page, 25 items per page")
                            .value("java"));
                }
                case "type" -> parameter.addExample(EXAMPLE_TYPE_FILTERED, new Example()
                        .summary("Filter to text content")
                        .value("text"));
                case "page" -> parameter.addExample(EXAMPLE_PAGINATED, new Example()
                        .summary("Third page")
                        .value(3));
                case "limit" -> parameter.addExample(EXAMPLE_PAGINATED, new Example()
                        .summary("25 items per page")
                        .value(25));
                default -> {
                    // No examples for other parameters (e.g. sort).
                }
            }
        }
    }

    private static void decorateSuccessResponseExamples(Operation operation) {
        MediaType mediaType = ensureJsonMediaType(operation, STATUS_OK, "OK");
        mediaType.addExamples(EXAMPLE_KEYWORD_ONLY, new Example()
                .summary("Keyword search, default sort=score, page=1, limit=10")
                .description("Response for GET /api/v1/search?q=java")
                .value("""
                        {
                          "data": [
                            {
                              "id": "0a1b2c3d-4e5f-6789-abcd-ef0123456789",
                              "title": "Java 21 in Action",
                              "type": "video",
                              "score": 42.1
                            }
                          ],
                          "pagination": { "page": 1, "limit": 10, "total": 1 }
                        }"""));
        mediaType.addExamples(EXAMPLE_TYPE_FILTERED, new Example()
                .summary("Filter to type=text")
                .description("Response for GET /api/v1/search?q=microservices&type=text")
                .value("""
                        {
                          "data": [
                            {
                              "id": "11111111-2222-3333-4444-555555555555",
                              "title": "Designing Microservices",
                              "type": "text",
                              "score": 31.7
                            }
                          ],
                          "pagination": { "page": 1, "limit": 10, "total": 1 }
                        }"""));
        mediaType.addExamples(EXAMPLE_PAGINATED, new Example()
                .summary("Page 3 with limit=25")
                .description("Response for GET /api/v1/search?q=java&page=3&limit=25")
                .value("""
                        {
                          "data": [
                            {
                              "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                              "title": "Java Concurrency Recipes",
                              "type": "text",
                              "score": 18.4
                            }
                          ],
                          "pagination": { "page": 3, "limit": 25, "total": 137 }
                        }"""));
    }

    private static void decorateValidationErrorResponse(Operation operation) {
        ApiResponses responses = ensureResponses(operation);
        ApiResponse badRequest = responses.computeIfAbsent(STATUS_BAD_REQUEST,
                k -> new ApiResponse().description(
                        "Validation error — matches the GlobalExceptionHandler error envelope (REQ 14.1, 14.2, 15.3)."));
        if (badRequest.getDescription() == null) {
            badRequest.setDescription(
                    "Validation error — matches the GlobalExceptionHandler error envelope (REQ 14.1, 14.2, 15.3).");
        }

        Content content = badRequest.getContent();
        if (content == null) {
            content = new Content();
            badRequest.setContent(content);
        }

        MediaType mediaType = content.computeIfAbsent(JSON_MEDIA_TYPE, k -> new MediaType());
        mediaType.schema(new Schema<>().$ref("#/components/schemas/" + ERROR_RESPONSE_SCHEMA));
        mediaType.addExamples("invalid-query", new Example()
                .summary("INVALID_QUERY (validation rejected the request)")
                .value("""
                        { "error": { "code": "INVALID_QUERY", "message": "q must be 1..200 chars" } }"""));
    }

    private static MediaType ensureJsonMediaType(Operation operation, String status, String defaultDescription) {
        ApiResponses responses = ensureResponses(operation);
        ApiResponse response = responses.computeIfAbsent(status,
                k -> new ApiResponse().description(defaultDescription));
        if (response.getDescription() == null) {
            response.setDescription(defaultDescription);
        }
        Content content = response.getContent();
        if (content == null) {
            content = new Content();
            response.setContent(content);
        }
        return content.computeIfAbsent(JSON_MEDIA_TYPE, k -> new MediaType());
    }

    private static ApiResponses ensureResponses(Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }
        return responses;
    }
}
