package io.kestra.plugin.fastly;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Shared HTTP execution layer for all Fastly API calls.
 * Centralised here so both tasks (extending AbstractFastlyTask) and triggers
 * (extending AbstractTrigger, which cannot extend AbstractFastlyTask) can reuse
 * the same auth, error-handling, and query-string logic.
 */
public final class FastlyClient {

    public static final ObjectMapper MAPPER = JacksonMapper.ofJson(false);

    private FastlyClient() {}

    /**
     * Performs a Fastly API request, handling auth headers, query string encoding,
     * optional extra headers, and non-2xx error surfacing.
     *
     * @param runContext    used to create the underlying {@link HttpClient}
     * @param token         rendered Fastly API token (must not be blank)
     * @param baseUrl       rendered base URL with trailing slash already stripped
     * @param options       HTTP configuration (may be null — defaults are applied internally)
     * @param method        HTTP method, e.g. {@code "GET"} or {@code "POST"}
     * @param path          URL path starting with {@code /}, e.g. {@code /stats/service/abc}
     * @param query         optional query parameters; null/blank values are skipped
     * @param extraHeaders  optional additional headers (e.g. {@code Fastly-Soft-Purge: 1})
     * @param body          optional request body; pass {@code null} for GET requests
     */
    public static HttpResponse<String> request(
        RunContext runContext,
        String token,
        String baseUrl,
        HttpConfiguration options,
        String method,
        String path,
        Map<String, String> query,
        Map<String, String> extraHeaders,
        HttpRequest.RequestBody body
    ) throws IOException, IllegalVariableEvaluationException, HttpClientException {
        var url = baseUrl + path + buildQueryString(query);

        var requestBuilder = HttpRequest.builder()
            .method(method)
            .uri(URI.create(url))
            .addHeader("Fastly-Key", token)
            .addHeader("Accept", "application/json");

        if (extraHeaders != null) {
            extraHeaders.forEach(requestBuilder::addHeader);
        }

        if (body != null) {
            requestBuilder.body(body);
        }

        var config = options != null ? options : HttpConfiguration.builder().build();
        config = config.toBuilder().allowFailed(Property.ofValue(true)).build();

        HttpResponse<String> response;
        try (var client = new HttpClient(runContext, config)) {
            response = client.request(requestBuilder.build(), String.class);
        }

        if (response.getStatus() == null || response.getStatus().getCode() == 0) {
            throw new HttpClientResponseException(
                "No response received from the Fastly API (possible connectivity, DNS, or timeout issue).",
                response
            );
        }

        var statusCode = response.getStatus().getCode();
        if (statusCode < 200 || statusCode >= 300) {
            var responseBody = response.getBody();
            if (statusCode == 403) {
                throw new HttpClientResponseException(
                    "Fastly API returned 403 Forbidden. Verify your API token has the required scope."
                        + " Response: " + responseBody,
                    response
                );
            }
            throw new HttpClientResponseException(
                "Fastly API request failed (HTTP " + statusCode + "): " + responseBody,
                response
            );
        }
        return response;
    }

    public static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Renders a required string property, failing with a clear, named error when it is
     * absent or blank after rendering.
     */
    public static String renderRequired(RunContext runContext, Property<String> property, String name)
        throws IllegalVariableEvaluationException {
        return runContext.render(property).as(String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException(name + " is required"));
    }

    public static String renderBaseUrl(RunContext runContext, Property<String> baseUrl)
        throws IllegalVariableEvaluationException {
        var raw = runContext.render(baseUrl).as(String.class)
            .filter(s -> !s.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("Fastly API base URL is required but was blank after rendering."));
        return normalizeBaseUrl(raw);
    }

    /** Strips trailing slash from a base URL string. */
    public static String normalizeBaseUrl(String raw) {
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    static String buildQueryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            var value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            sb.append(sb.isEmpty() ? "?" : "&");
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
