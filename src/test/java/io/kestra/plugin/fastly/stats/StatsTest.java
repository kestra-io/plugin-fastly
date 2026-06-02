package io.kestra.plugin.fastly.stats;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28301)
@KestraTest
class StatsTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void happyPath_withServiceId_dataIsArray() throws Exception {
        // Per-service endpoint returns data as a JSON array of time-bucketed datapoints.
        // Previously the Map-typed Output.data field silently dropped this, yielding null.
        stubFor(
            get(urlPathEqualTo("/stats/service/svc-abc"))
                .willReturn(okJson("""
                    {
                      "status": "ok",
                      "meta": {"from": 1000, "to": 2000, "by": "hour"},
                      "msg": null,
                      "data": [{"start_time": 1000, "requests": 10, "hits": 8, "hit_ratio": 0.8}]
                    }
                    """))
        );

        var task = Stats.builder()
            .id("statsWithServiceArray")
            .type(Stats.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28301"))
            .serviceId(Property.ofValue("svc-abc"))
            .from(Property.ofValue("1 hour ago"))
            .to(Property.ofValue("now"))
            .by(Property.ofValue("hour"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("ok", output.getStatus());
        assertNotNull(output.getMeta());
        assertNotNull(output.getData());

        // F2: per-service response (List) is normalized to {serviceId: [points]} for a uniform shape.
        var dataMap = output.getData();
        assertTrue(dataMap.containsKey("svc-abc"), "Expected data keyed by serviceId");
        @SuppressWarnings("unchecked")
        var points = (List<Map<String, Object>>) dataMap.get("svc-abc");
        assertEquals(1, points.size());
        assertEquals(10, ((Number) points.getFirst().get("requests")).intValue());

        verify(getRequestedFor(urlPathEqualTo("/stats/service/svc-abc"))
            .withHeader("Fastly-Key", equalTo("test-token"))
            .withoutHeader("Authorization")
            .withQueryParam("from", equalTo("1 hour ago"))
            .withQueryParam("to", equalTo("now"))
            .withQueryParam("by", equalTo("hour")));
    }

    @Test
    void happyPath_allServices_dataIsMap() throws Exception {
        // All-services endpoint returns data as a JSON object keyed by service id.
        stubFor(
            get(urlPathEqualTo("/stats"))
                .willReturn(okJson("""
                    {
                      "status": "ok",
                      "meta": {"from": 1000, "to": 2000, "by": "day"},
                      "msg": null,
                      "data": {"svc-xyz": [{"requests": 55}]}
                    }
                    """))
        );

        var task = Stats.builder()
            .id("statsAllServicesMap")
            .type(Stats.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28301"))
            .from(Property.ofValue("yesterday"))
            .to(Property.ofValue("now"))
            .by(Property.ofValue("day"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("ok", output.getStatus());
        assertNotNull(output.getData());

        @SuppressWarnings("unchecked")
        var dataMap = (Map<String, Object>) output.getData();
        assertTrue(dataMap.containsKey("svc-xyz"));

        verify(getRequestedFor(urlPathEqualTo("/stats"))
            .withHeader("Fastly-Key", equalTo("test-token")));
    }

    @Test
    void happyPath_allServices() throws Exception {
        stubFor(
            get(urlPathEqualTo("/stats"))
                .willReturn(okJson("""
                    {
                      "status": "ok",
                      "meta": {"from": 1000, "to": 2000, "by": "day"},
                      "msg": null,
                      "data": {"svc-abc": [{"requests": 100}], "svc-def": [{"requests": 200}]}
                    }
                    """))
        );

        var task = Stats.builder()
            .id("statsAllServices")
            .type(Stats.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28301"))
            .from(Property.ofValue("yesterday"))
            .to(Property.ofValue("now"))
            .by(Property.ofValue("day"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("ok", output.getStatus());
        assertNotNull(output.getData());

        verify(getRequestedFor(urlPathEqualTo("/stats"))
            .withHeader("Fastly-Key", equalTo("test-token")));
    }

    @Test
    void allServices_withServicesFilter() throws Exception {
        stubFor(
            get(urlPathEqualTo("/stats"))
                .withQueryParam("services", equalTo("svc-abc,svc-def"))
                .willReturn(okJson("""
                    {"status":"ok","meta":{},"msg":null,"data":{}}
                    """))
        );

        var task = Stats.builder()
            .id("statsFiltered")
            .type(Stats.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28301"))
            .services(Property.ofValue("svc-abc,svc-def"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("ok", output.getStatus());

        verify(getRequestedFor(urlPathEqualTo("/stats"))
            .withQueryParam("services", equalTo("svc-abc,svc-def")));
    }

    @Test
    void noAuthorizationHeaderEmitted_regression() throws Exception {
        // Regression: FastlyClient.request() previously called config.toBuilder().build() which
        // materialized a BasicAuthConfiguration(null, null) → "Authorization: Basic bnVsbDpudWxs".
        // Fastly rejected every request with 401 regardless of a valid Fastly-Key token.
        stubFor(
            get(urlPathMatching("/stats.*"))
                .willReturn(okJson("""
                    {"status":"ok","meta":{},"msg":null,"data":{}}
                    """))
        );

        var task = Stats.builder()
            .id("statsNoAuthHeader")
            .type(Stats.class.getName())
            .apiToken(Property.ofValue("valid-token"))
            .baseUrl(Property.ofValue("http://localhost:28301"))
            .build();

        task.run(runContextFactory.of());

        verify(getRequestedFor(urlPathMatching("/stats.*"))
            .withHeader("Fastly-Key", equalTo("valid-token"))
            .withoutHeader("Authorization"));
    }

    @Test
    void nonTwoXx_403_throwsWithMessage() {
        stubFor(
            get(urlPathMatching("/stats/service/.*"))
                .willReturn(aResponse().withStatus(403).withBody("Forbidden"))
        );

        var task = Stats.builder()
            .id("stats403")
            .type(Stats.class.getName())
            .apiToken(Property.ofValue("bad-token"))
            .baseUrl(Property.ofValue("http://localhost:28301"))
            .serviceId(Property.ofValue("svc-abc"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("403"), "Expected 403 in error message");
    }

    @Test
    void region_isPassedAsQueryParam() throws Exception {
        stubFor(
            get(urlPathEqualTo("/stats/service/svc-abc"))
                .withQueryParam("region", equalTo("usa"))
                .willReturn(okJson("""
                    {"status":"ok","meta":{},"msg":null,"data":{}}
                    """))
        );

        var task = Stats.builder()
            .id("statsRegion")
            .type(Stats.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28301"))
            .serviceId(Property.ofValue("svc-abc"))
            .region(Property.ofValue("usa"))
            .build();

        var output = task.run(runContextFactory.of());
        assertNotNull(output);

        verify(getRequestedFor(urlPathEqualTo("/stats/service/svc-abc"))
            .withQueryParam("region", equalTo("usa")));
    }
}
