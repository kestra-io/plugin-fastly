package io.kestra.plugin.fastly.stats;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28303)
@KestraTest
class UsageTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void happyPath_byRegion() throws Exception {
        stubFor(
            get(urlPathEqualTo("/stats/usage"))
                .willReturn(okJson("""
                    {
                      "status": "ok",
                      "meta": {},
                      "msg": null,
                      "data": {"usa": {"requests": 10000, "bandwidth": 5000000}}
                    }
                    """))
        );

        var task = Usage.builder()
            .id("usageByRegion")
            .type(Usage.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28303"))
            .from(Property.ofValue("yesterday"))
            .to(Property.ofValue("now"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("ok", output.getStatus());
        assertNotNull(output.getData());

        verify(getRequestedFor(urlPathEqualTo("/stats/usage"))
            .withHeader("Fastly-Key", equalTo("test-token"))
            .withQueryParam("from", equalTo("yesterday"))
            .withQueryParam("to", equalTo("now")));
    }

    @Test
    void happyPath_byService() throws Exception {
        stubFor(
            get(urlPathEqualTo("/stats/usage_by_service"))
                .willReturn(okJson("""
                    {
                      "status": "ok",
                      "meta": {},
                      "msg": null,
                      "data": {"usa": {"svc-abc": {"requests": 3000}}}
                    }
                    """))
        );

        var task = Usage.builder()
            .id("usageByService")
            .type(Usage.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28303"))
            .from(Property.ofValue("1 week ago"))
            .to(Property.ofValue("now"))
            .byService(Property.ofValue(true))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("ok", output.getStatus());
        assertNotNull(output.getData());

        verify(getRequestedFor(urlPathEqualTo("/stats/usage_by_service"))
            .withHeader("Fastly-Key", equalTo("test-token")));
    }

    @Test
    void nonTwoXx_403_throwsWithMessage() {
        stubFor(
            get(urlPathEqualTo("/stats/usage"))
                .willReturn(aResponse().withStatus(403).withBody("Forbidden"))
        );

        var task = Usage.builder()
            .id("usage403")
            .type(Usage.class.getName())
            .apiToken(Property.ofValue("bad-token"))
            .baseUrl(Property.ofValue("http://localhost:28303"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("403"), "Expected 403 in error message");
    }
}
