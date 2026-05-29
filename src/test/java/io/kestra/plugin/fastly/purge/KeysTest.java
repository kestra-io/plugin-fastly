package io.kestra.plugin.fastly.purge;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28202)
@KestraTest
class KeysTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void happyPath() throws Exception {
        stubFor(
            post(urlEqualTo("/service/svc-456/purge"))
                .willReturn(okJson("""
                    {"key-a":"purge-id-1","key-b":"purge-id-2"}
                    """))
        );

        var task = Keys.builder()
            .id("purgeKeys")
            .type(Keys.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28202"))
            .serviceId(Property.ofValue("svc-456"))
            .surrogateKeys(Property.ofValue(List.of("key-a", "key-b")))
            .soft(Property.ofValue(false))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertNotNull(output.getPurgeIds());
        assertEquals("purge-id-1", output.getPurgeIds().get("key-a"));
        assertEquals("purge-id-2", output.getPurgeIds().get("key-b"));

        verify(postRequestedFor(urlEqualTo("/service/svc-456/purge"))
            .withHeader("Fastly-Key", equalTo("test-token"))
            .withRequestBody(matchingJsonPath("$.surrogate_keys[0]", equalTo("key-a")))
            .withRequestBody(matchingJsonPath("$.surrogate_keys[1]", equalTo("key-b")))
            .withoutHeader("Fastly-Soft-Purge"));
    }

    @Test
    void softPurge_setsHeader() throws Exception {
        stubFor(
            post(urlEqualTo("/service/svc-456/purge"))
                .willReturn(okJson("""
                    {"key-x":"purge-id-soft"}
                    """))
        );

        var task = Keys.builder()
            .id("purgeKeysSoft")
            .type(Keys.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28202"))
            .serviceId(Property.ofValue("svc-456"))
            .surrogateKeys(Property.ofValue(List.of("key-x")))
            .soft(Property.ofValue(true))
            .build();

        var output = task.run(runContextFactory.of());
        assertEquals("purge-id-soft", output.getPurgeIds().get("key-x"));

        verify(postRequestedFor(urlEqualTo("/service/svc-456/purge"))
            .withHeader("Fastly-Soft-Purge", equalTo("1")));
    }

    @Test
    void emptyKeys_throwsBeforeCallingApi() {
        var task = Keys.builder()
            .id("purgeKeysEmpty")
            .type(Keys.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28202"))
            .serviceId(Property.ofValue("svc-456"))
            .surrogateKeys(Property.ofValue(List.of()))
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));
    }

    @Test
    void nonTwoXx_403_throwsWithMessage() {
        stubFor(
            post(urlPathMatching("/service/.*/purge"))
                .willReturn(aResponse().withStatus(403).withBody("Forbidden"))
        );

        var task = Keys.builder()
            .id("purgeKeys403")
            .type(Keys.class.getName())
            .apiToken(Property.ofValue("bad-token"))
            .baseUrl(Property.ofValue("http://localhost:28202"))
            .serviceId(Property.ofValue("svc-456"))
            .surrogateKeys(Property.ofValue(List.of("key-a")))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("403"), "Expected 403 in error message");
    }

    @Test
    void nonTwoXx_500_throwsWithStatus() {
        stubFor(
            post(urlPathMatching("/service/.*/purge"))
                .willReturn(aResponse().withStatus(500).withBody("Server Error"))
        );

        var task = Keys.builder()
            .id("purgeKeys500")
            .type(Keys.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28202"))
            .serviceId(Property.ofValue("svc-456"))
            .surrogateKeys(Property.ofValue(List.of("key-a")))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("500"), "Expected HTTP 500 in error message");
    }
}
