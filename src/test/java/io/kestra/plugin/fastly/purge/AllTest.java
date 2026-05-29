package io.kestra.plugin.fastly.purge;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28203)
@KestraTest
class AllTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void happyPath() throws Exception {
        stubFor(
            post(urlEqualTo("/service/svc-789/purge_all"))
                .willReturn(okJson("""
                    {"status":"ok"}
                    """))
        );

        var task = All.builder()
            .id("purgeAll")
            .type(All.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28203"))
            .serviceId(Property.ofValue("svc-789"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("ok", output.getStatus());

        // Soft-purge header must never be set for purge_all
        verify(postRequestedFor(urlEqualTo("/service/svc-789/purge_all"))
            .withHeader("Fastly-Key", equalTo("test-token"))
            .withoutHeader("Fastly-Soft-Purge"));
    }

    @Test
    void nonTwoXx_403_throwsWithMessage() {
        stubFor(
            post(urlPathMatching("/service/.*/purge_all"))
                .willReturn(aResponse().withStatus(403).withBody("Forbidden"))
        );

        var task = All.builder()
            .id("purgeAll403")
            .type(All.class.getName())
            .apiToken(Property.ofValue("bad-token"))
            .baseUrl(Property.ofValue("http://localhost:28203"))
            .serviceId(Property.ofValue("svc-789"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("403"), "Expected 403 in error message");
        assertTrue(ex.getMessage().contains("purge_all") || ex.getMessage().toLowerCase().contains("scope"),
            "Expected purge_all scope hint in error message");
    }

    @Test
    void nonTwoXx_500_throwsWithStatus() {
        stubFor(
            post(urlPathMatching("/service/.*/purge_all"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error"))
        );

        var task = All.builder()
            .id("purgeAll500")
            .type(All.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28203"))
            .serviceId(Property.ofValue("svc-789"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("500"), "Expected HTTP 500 in error message");
    }
}
