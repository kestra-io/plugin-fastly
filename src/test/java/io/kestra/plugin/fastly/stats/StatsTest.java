package io.kestra.plugin.fastly.stats;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28301)
@KestraTest
class StatsTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void happyPath_withServiceId() throws Exception {
        stubFor(
            get(urlPathEqualTo("/stats/service/svc-abc"))
                .willReturn(okJson("""
                    {
                      "status": "ok",
                      "meta": {"from": 1000, "to": 2000, "by": "hour"},
                      "msg": null,
                      "data": {"svc-abc": [{"requests": 42, "hit_ratio": 0.91}]}
                    }
                    """))
        );

        var task = Stats.builder()
            .id("statsWithService")
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

        verify(getRequestedFor(urlPathEqualTo("/stats/service/svc-abc"))
            .withHeader("Fastly-Key", equalTo("test-token"))
            .withQueryParam("from", equalTo("1 hour ago"))
            .withQueryParam("to", equalTo("now"))
            .withQueryParam("by", equalTo("hour")));
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
