package com.example.searchengine.web.openapi;

import com.example.searchengine.application.analytics.SearchAnalyticsRecorder;
import com.example.searchengine.infrastructure.admin.ClientIpHasher;
import com.example.searchengine.infrastructure.ratelimit.ClientIpResolver;
import com.example.searchengine.web.api.SearchController;
import com.example.searchengine.web.api.SearchRequest;

import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenApiConfig}.
 *
 * <p>Verifies (REQ 15.1, 15.2, 15.3, 15.4):</p>
 * <ul>
 *   <li>The {@link OpenAPI} bean is populated with API metadata and the
 *       shared {@code ErrorResponse} schema.</li>
 *   <li>The search-operation customizer registers the three named examples
 *       (<em>keyword-only</em>, <em>type-filtered</em>, <em>paginated</em>)
 *       on both the request parameter and the 200 response body.</li>
 *   <li>The 400 response references the shared {@code ErrorResponse} schema,
 *       matching the {@code GlobalExceptionHandler} envelope.</li>
 *   <li>The customizer is a no-op for unrelated handler methods.</li>
 * </ul>
 */
class OpenApiConfigTest {

    private OpenApiConfig config;

    @BeforeEach
    void setUp() {
        config = new OpenApiConfig();
    }

    @Test
    @DisplayName("customOpenAPI registers metadata and the ErrorResponse schema (REQ 15.1, 15.2, 15.3)")
    void customOpenApiBeanCarriesMetadataAndErrorSchema() {
        OpenAPI openApi = config.customOpenAPI();

        assertThat(openApi.getInfo()).isNotNull();
        assertThat(openApi.getInfo().getTitle()).isEqualTo("Search Engine Aggregator API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("v1");
        assertThat(openApi.getInfo().getDescription()).isNotBlank();

        assertThat(openApi.getComponents()).isNotNull();
        assertThat(openApi.getComponents().getSchemas())
                .containsKey(OpenApiConfig.ERROR_RESPONSE_SCHEMA);

        Schema<?> errorSchema = openApi.getComponents().getSchemas()
                .get(OpenApiConfig.ERROR_RESPONSE_SCHEMA);
        assertThat(errorSchema.getProperties()).containsKey("error");

        @SuppressWarnings("unchecked")
        Schema<Object> innerErrorSchema = (Schema<Object>) errorSchema.getProperties().get("error");
        assertThat(innerErrorSchema.getProperties()).containsOnlyKeys("code", "message");
        assertThat(innerErrorSchema.getRequired()).contains("code", "message");
    }

    @Test
    @DisplayName("searchOperationCustomizer registers the three required examples (REQ 15.4)")
    void customizerAddsThreeExamplesToSearchOperation() throws Exception {
        Operation operation = freshSearchOperation();
        OperationCustomizer customizer = config.searchOperationCustomizer();

        customizer.customize(operation, searchHandlerMethod());

        Parameter qParameter = operation.getParameters().stream()
                .filter(p -> "q".equals(p.getName()))
                .findFirst()
                .orElseThrow();
        Map<String, Example> qExamples = qParameter.getExamples();
        assertThat(qExamples).containsKeys(
                OpenApiConfig.EXAMPLE_KEYWORD_ONLY,
                OpenApiConfig.EXAMPLE_TYPE_FILTERED,
                OpenApiConfig.EXAMPLE_PAGINATED);
        assertThat(qExamples.get(OpenApiConfig.EXAMPLE_KEYWORD_ONLY).getValue())
                .isEqualTo("java");
        assertThat(qExamples.get(OpenApiConfig.EXAMPLE_TYPE_FILTERED).getValue())
                .isEqualTo("microservices");

        ApiResponse okResponse = operation.getResponses().get("200");
        assertThat(okResponse).isNotNull();
        MediaType okJson = okResponse.getContent().get("application/json");
        assertThat(okJson.getExamples()).containsKeys(
                OpenApiConfig.EXAMPLE_KEYWORD_ONLY,
                OpenApiConfig.EXAMPLE_TYPE_FILTERED,
                OpenApiConfig.EXAMPLE_PAGINATED);
    }

    @Test
    @DisplayName("400 response references the ErrorResponse schema (REQ 15.3)")
    void customizerAttachesValidationErrorResponse() throws Exception {
        Operation operation = freshSearchOperation();
        OperationCustomizer customizer = config.searchOperationCustomizer();

        customizer.customize(operation, searchHandlerMethod());

        ApiResponse badRequest = operation.getResponses().get("400");
        assertThat(badRequest).isNotNull();
        assertThat(badRequest.getDescription()).contains("Validation");

        MediaType json = badRequest.getContent().get("application/json");
        assertThat(json).isNotNull();
        assertThat(json.getSchema()).isNotNull();
        assertThat(json.getSchema().get$ref())
                .isEqualTo("#/components/schemas/" + OpenApiConfig.ERROR_RESPONSE_SCHEMA);

        Map<String, Example> examples = json.getExamples();
        assertThat(examples).isNotEmpty();
        Object exampleValue = examples.values().iterator().next().getValue();
        assertThat(String.valueOf(exampleValue))
                .contains("\"code\"")
                .contains("INVALID_QUERY")
                .contains("\"message\"");
    }

    @Test
    @DisplayName("customizer leaves unrelated operations untouched")
    void customizerIgnoresOtherEndpoints() throws Exception {
        Operation operation = new Operation();
        operation.setResponses(new ApiResponses());

        // A handler from a different controller-style class exposed via reflection.
        HandlerMethod otherHandler = new HandlerMethod(
                this,
                OpenApiConfigTest.class.getDeclaredMethod("placeholderHandler"));

        OperationCustomizer customizer = config.searchOperationCustomizer();
        Operation result = customizer.customize(operation, otherHandler);

        assertThat(result.getParameters()).isNull();
        assertThat(result.getResponses()).isEmpty();
    }

    private static Operation freshSearchOperation() {
        Operation operation = new Operation();
        operation.addParametersItem(new Parameter().name("q").in("query"));
        operation.addParametersItem(new Parameter().name("type").in("query"));
        operation.addParametersItem(new Parameter().name("sort").in("query"));
        operation.addParametersItem(new Parameter().name("page").in("query"));
        operation.addParametersItem(new Parameter().name("limit").in("query"));
        return operation;
    }

    private static HandlerMethod searchHandlerMethod() throws NoSuchMethodException {
        Method searchMethod = SearchController.class
                .getDeclaredMethod("search", SearchRequest.class,
                        HttpServletRequest.class);
        SearchController stub = new SearchController(null, null, null, null);
        return new HandlerMethod(stub, searchMethod);
    }

    /** Placeholder handler used to verify the customizer skips non-search endpoints. */
    @SuppressWarnings("unused")
    public void placeholderHandler() {
        // intentionally empty
    }
}
