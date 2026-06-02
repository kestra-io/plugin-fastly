package io.kestra.plugin.fastly.stats;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28302)
@KestraTest
class AggregateStatsTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void happyPath() throws Exception {
        stubFor(
            get(urlPathEqualTo("/stats/aggregate"))
                .willReturn(okJson("""
                    {
                      "status": "ok",
                      "meta": {"from": 1000, "to": 2000, "by": "hour"},
                      "msg": null,
                      "data": [{"requests": 500, "bandwidth": 102400, "hit_ratio": 0.87}]
                    }
                    """))
        );

        var task = AggregateStats.builder()
            .id("aggregateStats")
            .type(AggregateStats.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28302"))
            .from(Property.ofValue("24 hours ago"))
            .to(Property.ofValue("now"))
            .by(Property.ofValue("hour"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("ok", output.getStatus());
        assertNotNull(output.getMeta());
        assertNotNull(output.getData());

        verify(getRequestedFor(urlPathEqualTo("/stats/aggregate"))
            .withHeader("Fastly-Key", equalTo("test-token"))
            .withQueryParam("from", equalTo("24 hours ago"))
            .withQueryParam("to", equalTo("now"))
            .withQueryParam("by", equalTo("hour")));
    }

    @Test
    void nonTwoXx_500_throwsWithStatus() {
        stubFor(
            get(urlPathEqualTo("/stats/aggregate"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error"))
        );

        var task = AggregateStats.builder()
            .id("aggregateStats500")
            .type(AggregateStats.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28302"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("500"), "Expected HTTP 500 in error message");
    }

    @Test
    void regionParam_isForwarded() throws Exception {
        stubFor(
            get(urlPathEqualTo("/stats/aggregate"))
                .withQueryParam("region", equalTo("europe"))
                .willReturn(okJson("""
                    {"status":"ok","meta":{},"msg":null,"data":[]}
                    """))
        );

        var task = AggregateStats.builder()
            .id("aggregateStatsRegion")
            .type(AggregateStats.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28302"))
            .region(Property.ofValue("europe"))
            .build();

        var output = task.run(runContextFactory.of());
        assertNotNull(output);

        verify(getRequestedFor(urlPathEqualTo("/stats/aggregate"))
            .withQueryParam("region", equalTo("europe")));
    }
}
