package io.kestra.plugin.fastly;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractFastlyTask extends Task {

    protected static final ObjectMapper MAPPER = FastlyClient.MAPPER;

    @Schema(
        title = "Fastly API token",
        description = """
            Your Fastly API token. Create one in the Fastly console under Account > API tokens.
            Required scopes depend on the operation: URL and surrogate key purges require `purge_select`;
            purge-all requires `purge_all`; stats endpoints require `global:read`.
            """
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    protected Property<String> apiToken;

    @Schema(
        title = "Fastly API base URL",
        description = "Base URL for the Fastly API. Override this only for testing."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl = Property.ofValue("https://api.fastly.com");

    @Schema(
        title = "HTTP client options",
        description = "Optional advanced HTTP settings such as timeouts or proxy configuration."
    )
    @PluginProperty(group = "advanced")
    protected HttpConfiguration options;

    /**
     * Executes a POST request against the Fastly API, injects auth and common headers,
     * optionally sets the soft-purge header, and returns the raw response body as a String.
     * Non-2xx responses are surfaced as an exception with status code and body included.
     */
    protected HttpResponse<String> fastlyRequest(
        RunContext runContext,
        String path,
        boolean softPurge,
        HttpRequest.RequestBody body
    ) throws IllegalVariableEvaluationException, HttpClientException {
        var rToken = renderToken(runContext);
        var rBaseUrl = renderBaseUrl(runContext);

        var extraHeaders = softPurge ? Map.of("Fastly-Soft-Purge", "1") : Map.<String, String>of();
        try {
            return FastlyClient.request(runContext, rToken, rBaseUrl, options, "POST", path, null, extraHeaders, body);
        } catch (IOException e) {
            throw new RuntimeException("Failed to call Fastly API", e);
        }
    }

    /**
     * Executes a GET request against the Fastly API with optional query parameters.
     */
    protected HttpResponse<String> fastlyGet(
        RunContext runContext,
        String path,
        Map<String, String> query
    ) throws IllegalVariableEvaluationException, HttpClientException {
        var rToken = renderToken(runContext);
        var rBaseUrl = renderBaseUrl(runContext);
        try {
            return FastlyClient.request(runContext, rToken, rBaseUrl, options, "GET", path, query, null, null);
        } catch (IOException e) {
            throw new RuntimeException("Failed to call Fastly API", e);
        }
    }

    /**
     * Parses the standard Fastly stats response envelope {@code {status, meta, data}},
     * tolerating an empty body. The {@code data} field may be a Map or List depending
     * on the endpoint; it is returned as {@code Object} and will be a {@code Map} or
     * {@code List} at runtime.
     */
    protected StatsEnvelope readStatsEnvelope(HttpResponse<String> response) throws IOException {
        var body = response.getBody();
        if (body == null || body.isBlank()) {
            return new StatsEnvelope(null, null, null);
        }
        return MAPPER.readValue(body, StatsEnvelope.class);
    }

    protected static String encodePathSegment(String segment) {
        return FastlyClient.encodePathSegment(segment);
    }

    /**
     * Renders a required string property, failing with a clear, named error when it is
     * absent or blank after rendering.
     */
    protected String renderRequired(RunContext runContext, Property<String> property, String name)
        throws IllegalVariableEvaluationException {
        return FastlyClient.renderRequired(runContext, property, name);
    }

    /**
     * Parses a Fastly purge response body, tolerating an empty or absent body so a purge that
     * succeeded server-side is not reported as failed when no JSON payload is returned.
     */
    protected PurgeResponse readPurgeResponse(HttpResponse<String> response) throws IOException {
        var body = response.getBody();
        if (body == null || body.isBlank()) {
            return new PurgeResponse(null, null);
        }
        return MAPPER.readValue(body, PurgeResponse.class);
    }

    /**
     * Parses a Fastly batch-purge response body (a map of surrogate key to purge ID), tolerating
     * an empty or absent body.
     */
    protected Map<String, String> readPurgeIds(HttpResponse<String> response) throws IOException {
        var body = response.getBody();
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        return MAPPER.readValue(body, new TypeReference<>() {});
    }

    private String renderToken(RunContext runContext) throws IllegalVariableEvaluationException {
        var rToken = runContext.render(apiToken).as(String.class).orElse(null);
        if (rToken == null || rToken.isBlank()) {
            throw new IllegalArgumentException("Fastly API token is required but was blank after rendering.");
        }
        return rToken;
    }

    private String renderBaseUrl(RunContext runContext) throws IllegalVariableEvaluationException {
        return FastlyClient.renderBaseUrl(runContext, baseUrl);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PurgeResponse(String status, String id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatsEnvelope(String status, Map<String, Object> meta, Object data) {}
}
