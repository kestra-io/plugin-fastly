package io.kestra.plugin.fastly.stats;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28305)
@KestraTest
class StatsTriggerTest {

    private static final String FIELD_RESPONSE_TEMPLATE = """
        {
          "status": "ok",
          "meta": {},
          "msg": null,
          "data": {
            "svc-test": [
              {"status_5xx": %s, "start": 1000},
              {"status_5xx": %s, "start": 1060}
            ]
          }
        }
        """;

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void thresholdCrossed_returnsExecution() throws Exception {
        // 30 + 40 = 70, threshold = 50, GREATER_THAN -> fires
        stubFor(
            get(urlPathEqualTo("/stats/service/svc-test/field/status_5xx"))
                .willReturn(okJson(FIELD_RESPONSE_TEMPLATE.formatted("30", "40")))
        );

        var trigger = StatsTrigger.builder()
            .id("errorAlert")
            .type(StatsTrigger.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28305"))
            .serviceId(Property.ofValue("svc-test"))
            .field(Property.ofValue("status_5xx"))
            .threshold(Property.ofValue(50.0))
            .comparator(Property.ofValue(StatsTrigger.ComparisonOperator.GREATER_THAN))
            .window(Property.ofValue(Duration.ofHours(1)))
            .interval(Duration.ofMinutes(5))
            .build();

        var mockEntry = TestsUtils.mockTrigger(runContextFactory, trigger);
        var conditionContext = mockEntry.getKey();
        var triggerContext = mockEntry.getValue();

        var result = trigger.evaluate(conditionContext, triggerContext);

        assertTrue(result.isPresent(), "Expected execution to be fired when threshold is crossed");
        var vars = result.get().getTrigger().getVariables();
        assertNotNull(vars.get("value"));
        assertEquals("status_5xx", vars.get("field"));
        assertEquals(50.0, ((Number) vars.get("threshold")).doubleValue(), 0.0001);
        assertEquals(70.0, ((Number) vars.get("value")).doubleValue(), 0.0001);
    }

    @Test
    void belowThreshold_returnsEmpty() throws Exception {
        // 10 + 5 = 15, threshold = 50, GREATER_THAN -> does not fire
        stubFor(
            get(urlPathEqualTo("/stats/service/svc-test/field/status_5xx"))
                .willReturn(okJson(FIELD_RESPONSE_TEMPLATE.formatted("10", "5")))
        );

        var trigger = StatsTrigger.builder()
            .id("errorAlertLow")
            .type(StatsTrigger.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28305"))
            .serviceId(Property.ofValue("svc-test"))
            .field(Property.ofValue("status_5xx"))
            .threshold(Property.ofValue(50.0))
            .comparator(Property.ofValue(StatsTrigger.ComparisonOperator.GREATER_THAN))
            .window(Property.ofValue(Duration.ofHours(1)))
            .interval(Duration.ofMinutes(5))
            .build();

        var mockEntry = TestsUtils.mockTrigger(runContextFactory, trigger);
        var result = trigger.evaluate(mockEntry.getKey(), mockEntry.getValue());

        assertFalse(result.isPresent(), "Expected no execution when observed value is below threshold");
    }

    // --- Regression tests for the real Fastly array-shaped response ---
    // The live endpoint returns "data" as a JSON array, not a map keyed by serviceId.
    // The old Map-only parsing always returned 0.0, making the trigger non-functional.

    @Test
    void arrayShape_thresholdCrossed_returnsExecution() throws Exception {
        // 600 + 700 = 1300, threshold = 1000, GREATER_THAN -> must fire
        stubFor(
            get(urlPathEqualTo("/stats/service/svc-test/field/status_5xx"))
                .willReturn(okJson("""
                    {
                      "status": "success",
                      "meta": {},
                      "msg": null,
                      "data": [
                        {"start_time": 1712001600, "service_id": "svc-test", "status_5xx": 600},
                        {"start_time": 1712005200, "service_id": "svc-test", "status_5xx": 700}
                      ]
                    }
                    """))
        );

        var trigger = StatsTrigger.builder()
            .id("arrayShapeAlert")
            .type(StatsTrigger.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28305"))
            .serviceId(Property.ofValue("svc-test"))
            .field(Property.ofValue("status_5xx"))
            .threshold(Property.ofValue(1000.0))
            .comparator(Property.ofValue(StatsTrigger.ComparisonOperator.GREATER_THAN))
            .window(Property.ofValue(Duration.ofHours(1)))
            .interval(Duration.ofMinutes(5))
            .build();

        var mockEntry = TestsUtils.mockTrigger(runContextFactory, trigger);
        var result = trigger.evaluate(mockEntry.getKey(), mockEntry.getValue());

        assertTrue(result.isPresent(), "Expected execution to fire when array-shape sum (1300) exceeds threshold (1000)");
        var vars = result.get().getTrigger().getVariables();
        assertEquals(1300.0, ((Number) vars.get("value")).doubleValue(), 0.0001);
        assertEquals("status_5xx", vars.get("field"));
        assertEquals(1000.0, ((Number) vars.get("threshold")).doubleValue(), 0.0001);
    }

    @Test
    void arrayShape_belowThreshold_returnsEmpty() throws Exception {
        // 600 + 700 = 1300, threshold = 100000, GREATER_THAN -> must not fire
        stubFor(
            get(urlPathEqualTo("/stats/service/svc-test/field/status_5xx"))
                .willReturn(okJson("""
                    {
                      "status": "success",
                      "meta": {},
                      "msg": null,
                      "data": [
                        {"start_time": 1712001600, "service_id": "svc-test", "status_5xx": 600},
                        {"start_time": 1712005200, "service_id": "svc-test", "status_5xx": 700}
                      ]
                    }
                    """))
        );

        var trigger = StatsTrigger.builder()
            .id("arrayShapeAlertHigh")
            .type(StatsTrigger.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28305"))
            .serviceId(Property.ofValue("svc-test"))
            .field(Property.ofValue("status_5xx"))
            .threshold(Property.ofValue(100000.0))
            .comparator(Property.ofValue(StatsTrigger.ComparisonOperator.GREATER_THAN))
            .window(Property.ofValue(Duration.ofHours(1)))
            .interval(Duration.ofMinutes(5))
            .build();

        var mockEntry = TestsUtils.mockTrigger(runContextFactory, trigger);
        var result = trigger.evaluate(mockEntry.getKey(), mockEntry.getValue());

        assertFalse(result.isPresent(), "Expected no execution when array-shape sum (1300) is below threshold (100000)");
    }

    @Test
    void arrayShape_thirdDatapoint_summedCorrectly() throws Exception {
        // 100 + 200 + 300 = 600, threshold = 500, GREATER_THAN -> fires
        stubFor(
            get(urlPathEqualTo("/stats/service/svc-test/field/requests"))
                .willReturn(okJson("""
                    {
                      "status": "success",
                      "meta": {},
                      "msg": null,
                      "data": [
                        {"start_time": 1712001600, "requests": 100},
                        {"start_time": 1712005200, "requests": 200},
                        {"start_time": 1712008800, "requests": 300}
                      ]
                    }
                    """))
        );

        var trigger = StatsTrigger.builder()
            .id("arrayShape3Points")
            .type(StatsTrigger.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28305"))
            .serviceId(Property.ofValue("svc-test"))
            .field(Property.ofValue("requests"))
            .threshold(Property.ofValue(500.0))
            .comparator(Property.ofValue(StatsTrigger.ComparisonOperator.GREATER_THAN))
            .window(Property.ofValue(Duration.ofHours(1)))
            .interval(Duration.ofMinutes(5))
            .build();

        var mockEntry = TestsUtils.mockTrigger(runContextFactory, trigger);
        var result = trigger.evaluate(mockEntry.getKey(), mockEntry.getValue());

        assertTrue(result.isPresent(), "Expected execution when 3-point array sum (600) exceeds threshold (500)");
        assertEquals(600.0, ((Number) result.get().getTrigger().getVariables().get("value")).doubleValue(), 0.0001);
    }

    @Test
    void lessThan_comparator_firesWhenBelow() throws Exception {
        // 0.5 + 0.3 = 0.8, threshold = 0.9, LESS_THAN -> fires
        stubFor(
            get(urlPathEqualTo("/stats/service/svc-test/field/hit_ratio"))
                .willReturn(okJson("""
                    {
                      "status": "ok",
                      "meta": {},
                      "msg": null,
                      "data": {
                        "svc-test": [
                          {"hit_ratio": 0.5},
                          {"hit_ratio": 0.3}
                        ]
                      }
                    }
                    """))
        );

        var trigger = StatsTrigger.builder()
            .id("hitRatioAlert")
            .type(StatsTrigger.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28305"))
            .serviceId(Property.ofValue("svc-test"))
            .field(Property.ofValue("hit_ratio"))
            .threshold(Property.ofValue(0.9))
            .comparator(Property.ofValue(StatsTrigger.ComparisonOperator.LESS_THAN))
            .window(Property.ofValue(Duration.ofMinutes(5)))
            .interval(Duration.ofMinutes(1))
            .build();

        var mockEntry = TestsUtils.mockTrigger(runContextFactory, trigger);
        var result = trigger.evaluate(mockEntry.getKey(), mockEntry.getValue());

        assertTrue(result.isPresent(), "Expected execution when hit_ratio sum is below threshold");
    }
}
