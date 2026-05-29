package io.kestra.plugin.fastly.purge;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28200)
@KestraTest
class PurgeUrlTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void happyPath() throws Exception {
        stubFor(
            post(urlPathMatching("/purge/https://example.com/"))
                .willReturn(okJson("""
                    {"status":"ok","id":"req-url-001"}
                    """))
        );

        var task = PurgeUrl.builder()
            .id("purgeUrl")
            .type(PurgeUrl.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28200"))
            .url(Property.ofValue("https://example.com/"))
            .soft(Property.ofValue(false))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("ok", output.getStatus());
        assertEquals("req-url-001", output.getId());

        verify(postRequestedFor(urlPathMatching("/purge/https://example.com/"))
            .withHeader("Fastly-Key", equalTo("test-token"))
            .withoutHeader("Fastly-Soft-Purge"));
    }

    @Test
    void softPurge_setsHeader() throws Exception {
        stubFor(
            post(urlPathMatching("/purge/https://example.com/page"))
                .willReturn(okJson("""
                    {"status":"ok","id":"req-url-soft-001"}
                    """))
        );

        var task = PurgeUrl.builder()
            .id("purgeUrlSoft")
            .type(PurgeUrl.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28200"))
            .url(Property.ofValue("https://example.com/page"))
            .soft(Property.ofValue(true))
            .build();

        var output = task.run(runContextFactory.of());
        assertEquals("ok", output.getStatus());

        verify(postRequestedFor(urlPathMatching("/purge/https://example.com/page"))
            .withHeader("Fastly-Soft-Purge", equalTo("1")));
    }

    @Test
    void encodesSpacesAndNonAscii() throws Exception {
        stubFor(
            post(urlMatching("/purge/.*"))
                .willReturn(okJson("""
                    {"status":"ok","id":"req-url-enc-001"}
                    """))
        );

        var task = PurgeUrl.builder()
            .id("purgeUrlEncode")
            .type(PurgeUrl.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28200"))
            .url(Property.ofValue("https://example.com/a b/café?x=1"))
            .build();

        // Previously the raw URL was passed to URI.create and threw on the space / non-ASCII char.
        var output = task.run(runContextFactory.of());
        assertEquals("ok", output.getStatus());

        verify(postRequestedFor(urlMatching("/purge/https://example.com/a%20b/caf%C3%A9\\?x=1")));
    }

    @Test
    void emptyBodyOnSuccess_doesNotFail() throws Exception {
        stubFor(
            post(urlPathMatching("/purge/.*"))
                .willReturn(aResponse().withStatus(200))
        );

        var task = PurgeUrl.builder()
            .id("purgeUrlEmptyBody")
            .type(PurgeUrl.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28200"))
            .url(Property.ofValue("https://example.com/"))
            .build();

        var output = task.run(runContextFactory.of());
        assertNotNull(output);
        assertNull(output.getStatus());
        assertNull(output.getId());
    }

    @Test
    void trailingSlashBaseUrl_isNormalized() throws Exception {
        stubFor(
            post(urlPathMatching("/purge/https://example.com/"))
                .willReturn(okJson("""
                    {"status":"ok","id":"req-url-slash-001"}
                    """))
        );

        var task = PurgeUrl.builder()
            .id("purgeUrlTrailingSlash")
            .type(PurgeUrl.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28200/"))
            .url(Property.ofValue("https://example.com/"))
            .build();

        // A double slash (//purge/...) would not match the stub below.
        var output = task.run(runContextFactory.of());
        assertEquals("ok", output.getStatus());

        verify(postRequestedFor(urlPathMatching("/purge/https://example.com/")));
    }

    @Test
    void nonTwoXx_403_throwsWithMessage() {
        stubFor(
            post(urlPathMatching("/purge/.*"))
                .willReturn(aResponse().withStatus(403).withBody("Forbidden"))
        );

        var task = PurgeUrl.builder()
            .id("purgeUrl403")
            .type(PurgeUrl.class.getName())
            .apiToken(Property.ofValue("bad-token"))
            .baseUrl(Property.ofValue("http://localhost:28200"))
            .url(Property.ofValue("https://example.com/"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("403"), "Expected 403 in error message");
        assertTrue(ex.getMessage().toLowerCase().contains("scope") || ex.getMessage().contains("purge_select"),
            "Expected token scope hint in error message");
    }

    @Test
    void nonTwoXx_500_throwsWithStatus() {
        stubFor(
            post(urlPathMatching("/purge/.*"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error"))
        );

        var task = PurgeUrl.builder()
            .id("purgeUrl500")
            .type(PurgeUrl.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28200"))
            .url(Property.ofValue("https://example.com/"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("500"), "Expected HTTP 500 in error message");
    }
}
