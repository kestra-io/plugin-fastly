package io.kestra.plugin.fastly.purge;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28201)
@KestraTest
class PurgeKeyTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void happyPath() throws Exception {
        stubFor(
            post(urlEqualTo("/service/svc-123/purge/product-42"))
                .willReturn(okJson("""
                    {"status":"ok","id":"req-key-001"}
                    """))
        );

        var task = PurgeKey.builder()
            .id("purgeKey")
            .type(PurgeKey.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28201"))
            .serviceId(Property.ofValue("svc-123"))
            .surrogateKey(Property.ofValue("product-42"))
            .soft(Property.ofValue(false))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("ok", output.getStatus());
        assertEquals("req-key-001", output.getId());

        verify(postRequestedFor(urlEqualTo("/service/svc-123/purge/product-42"))
            .withHeader("Fastly-Key", equalTo("test-token"))
            .withoutHeader("Fastly-Soft-Purge"));
    }

    @Test
    void softPurge_setsHeader() throws Exception {
        stubFor(
            post(urlEqualTo("/service/svc-123/purge/article-7"))
                .willReturn(okJson("""
                    {"status":"ok","id":"req-key-soft-001"}
                    """))
        );

        var task = PurgeKey.builder()
            .id("purgeKeySoft")
            .type(PurgeKey.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28201"))
            .serviceId(Property.ofValue("svc-123"))
            .surrogateKey(Property.ofValue("article-7"))
            .soft(Property.ofValue(true))
            .build();

        var output = task.run(runContextFactory.of());
        assertEquals("ok", output.getStatus());

        verify(postRequestedFor(urlEqualTo("/service/svc-123/purge/article-7"))
            .withHeader("Fastly-Soft-Purge", equalTo("1")));
    }

    @Test
    void encodesSurrogateKeyPathSegment() throws Exception {
        stubFor(
            post(urlEqualTo("/service/svc-123/purge/key%20with%20space"))
                .willReturn(okJson("""
                    {"status":"ok","id":"req-key-enc-001"}
                    """))
        );

        var task = PurgeKey.builder()
            .id("purgeKeyEncode")
            .type(PurgeKey.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28201"))
            .serviceId(Property.ofValue("svc-123"))
            .surrogateKey(Property.ofValue("key with space"))
            .build();

        var output = task.run(runContextFactory.of());
        assertEquals("ok", output.getStatus());

        verify(postRequestedFor(urlEqualTo("/service/svc-123/purge/key%20with%20space")));
    }

    @Test
    void nonTwoXx_403_throwsWithMessage() {
        stubFor(
            post(urlPathMatching("/service/.*/purge/.*"))
                .willReturn(aResponse().withStatus(403).withBody("Forbidden"))
        );

        var task = PurgeKey.builder()
            .id("purgeKey403")
            .type(PurgeKey.class.getName())
            .apiToken(Property.ofValue("bad-token"))
            .baseUrl(Property.ofValue("http://localhost:28201"))
            .serviceId(Property.ofValue("svc-123"))
            .surrogateKey(Property.ofValue("product-42"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("403"), "Expected 403 in error message");
    }

    @Test
    void nonTwoXx_500_throwsWithStatus() {
        stubFor(
            post(urlPathMatching("/service/.*/purge/.*"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error"))
        );

        var task = PurgeKey.builder()
            .id("purgeKey500")
            .type(PurgeKey.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28201"))
            .serviceId(Property.ofValue("svc-123"))
            .surrogateKey(Property.ofValue("product-42"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("500"), "Expected HTTP 500 in error message");
    }
}
