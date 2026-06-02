package io.kestra.plugin.fastly.stats;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28304)
@KestraTest
class MonthToDateUsageTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void happyPath() throws Exception {
        stubFor(
            get(urlPathEqualTo("/stats/usage_by_month"))
                .willReturn(okJson("""
                    {
                      "status": "ok",
                      "meta": {},
                      "msg": null,
                      "data": {"usa": {"requests": 500000, "bandwidth": 250000000}}
                    }
                    """))
        );

        var task = MonthToDateUsage.builder()
            .id("mtdUsage")
            .type(MonthToDateUsage.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28304"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("ok", output.getStatus());
        assertNotNull(output.getData());

        verify(getRequestedFor(urlPathEqualTo("/stats/usage_by_month"))
            .withHeader("Fastly-Key", equalTo("test-token")));
    }

    @Test
    void nonTwoXx_500_throwsWithStatus() {
        stubFor(
            get(urlPathEqualTo("/stats/usage_by_month"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error"))
        );

        var task = MonthToDateUsage.builder()
            .id("mtdUsage500")
            .type(MonthToDateUsage.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28304"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("500"), "Expected HTTP 500 in error message");
    }

    @Test
    void nonTwoXx_403_throwsWithMessage() {
        stubFor(
            get(urlPathEqualTo("/stats/usage_by_month"))
                .willReturn(aResponse().withStatus(403).withBody("Forbidden"))
        );

        var task = MonthToDateUsage.builder()
            .id("mtdUsage403")
            .type(MonthToDateUsage.class.getName())
            .apiToken(Property.ofValue("bad-token"))
            .baseUrl(Property.ofValue("http://localhost:28304"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("403"), "Expected 403 in error message");
    }
}
