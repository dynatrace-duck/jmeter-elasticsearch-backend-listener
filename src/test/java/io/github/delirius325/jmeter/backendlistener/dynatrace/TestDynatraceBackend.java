package io.github.delirius325.jmeter.backendlistener.dynatrace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.junit.Before;
import org.junit.Test;

public class TestDynatraceBackend {

    private static final String TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZZ";
    private static final String DEPRECATED_METRIC_TOGGLE = "dt.metrics.enabled";
    private static final String DEPRECATED_METRIC_TOKEN = "dt.metrics.token";

    private SampleResult successfulSample;
    private SampleResult failedSample;

    @Before
    public void setUp() {
        successfulSample = new SampleResult();
        successfulSample.setSampleLabel("HTTP GET /api/health");
        successfulSample.setSuccessful(true);
        successfulSample.setResponseCode("200");

        failedSample = new SampleResult();
        failedSample.setSampleLabel("HTTP POST /api/login");
        failedSample.setSuccessful(false);
        failedSample.setResponseCode("500");
    }

    /**
     * Verifies that the DT-required "timestamp" and "content" fields are always present
     * in the metric output, regardless of field filters.
     */
    @Test
    public void testRequiredDtFieldsAlwaysPresent() throws Exception {
        Set<String> emptyFilter = new HashSet<>();
        DynatraceMetric metric = new DynatraceMetric(
                successfulSample, "info", TIMESTAMP_FORMAT, false, false, emptyFilter, "jmeter");

        Map<String, Object> result = metric.getMetric(null);

        assertNotNull("timestamp field must always be present", result.get("timestamp"));
        assertNotNull("content field must always be present", result.get("content"));
        assertEquals("content should equal SampleLabel",
                successfulSample.getSampleLabel(), result.get("content"));
    }

    /**
     * Verifies that the "timestamp" field is formatted as ISO 8601.
     */
    @Test
    public void testTimestampIsIso8601() throws Exception {
        DynatraceMetric metric = new DynatraceMetric(
                successfulSample, "info", TIMESTAMP_FORMAT, false, false, new HashSet<>(), "jmeter");

        Map<String, Object> result = metric.getMetric(null);

        String ts = (String) result.get("timestamp");
        assertNotNull(ts);
        // ISO 8601 timestamps contain a 'T' separator
        assertTrue("timestamp should be ISO 8601 format (contains 'T')", ts.contains("T"));
    }

    /**
     * Verifies that a field filter restricts the output to the specified fields,
     * while "timestamp" and "content" remain present regardless.
     */
    @Test
    public void testFieldFilterRestrictsOutput() throws Exception {
        Set<String> filter = new HashSet<>();
        filter.add("responsetime");
        filter.add("responsecode");

        DynatraceMetric metric = new DynatraceMetric(
                successfulSample, "info", TIMESTAMP_FORMAT, false, false, filter, "jmeter");

        Map<String, Object> result = metric.getMetric(null);

        // Filtered fields should be present
        assertNotNull("ResponseTime should be present", result.get("ResponseTime"));
        assertNotNull("ResponseCode should be present", result.get("ResponseCode"));

        // Non-filtered optional fields should be absent
        assertNull("AllThreads should be absent with field filter", result.get("AllThreads"));
        assertNull("Bytes should be absent with field filter", result.get("Bytes"));

        // DT-required fields must always be present even with a filter
        assertNotNull("timestamp must be present despite field filter", result.get("timestamp"));
        assertNotNull("content must be present despite field filter", result.get("content"));
        assertNotNull("log.source must be present despite field filter", result.get("log.source"));
    }

    /**
     * Verifies that request/response details are included for failed samples in "info" mode.
     */
    @Test
    public void testInfoModeAddsDetailsForFailedSamples() throws Exception {
        DynatraceMetric metric = new DynatraceMetric(
                failedSample, "info", TIMESTAMP_FORMAT, false, false, new HashSet<>(), "jmeter");

        Map<String, Object> result = metric.getMetric(null);

        assertNotNull("RequestHeaders should be present for failed sample in info mode",
                result.get("RequestHeaders"));
        assertNotNull("ResponseBody should be present for failed sample in info mode",
                result.get("ResponseBody"));
    }

    /**
     * Verifies that request/response details are NOT included for successful samples in "info" mode.
     */
    @Test
    public void testInfoModeOmitsDetailsForSuccessfulSamples() throws Exception {
        DynatraceMetric metric = new DynatraceMetric(
                successfulSample, "info", TIMESTAMP_FORMAT, false, false, new HashSet<>(), "jmeter");

        Map<String, Object> result = metric.getMetric(null);

        assertNull("RequestHeaders should be absent for successful sample in info mode",
                result.get("RequestHeaders"));
        assertNull("ResponseBody should be absent for successful sample in info mode",
                result.get("ResponseBody"));
    }

    /**
     * Verifies that "quiet" mode never adds request/response details.
     */
    @Test
    public void testQuietModeOmitsDetails() throws Exception {
        DynatraceMetric metric = new DynatraceMetric(
                failedSample, "quiet", TIMESTAMP_FORMAT, false, false, new HashSet<>(), "jmeter");

        Map<String, Object> result = metric.getMetric(null);

        assertNull("RequestHeaders should be absent in quiet mode", result.get("RequestHeaders"));
        assertNull("ResponseBody should be absent in quiet mode", result.get("ResponseBody"));
    }

    /**
     * Verifies that "debug" mode always adds request/response details.
     */
    @Test
    public void testDebugModeAddsDetailsAlways() throws Exception {
        DynatraceMetric metric = new DynatraceMetric(
                successfulSample, "debug", TIMESTAMP_FORMAT, false, false, new HashSet<>(), "jmeter");

        Map<String, Object> result = metric.getMetric(null);

        assertNotNull("RequestHeaders should be present in debug mode", result.get("RequestHeaders"));
        assertNotNull("ResponseBody should be present in debug mode", result.get("ResponseBody"));
    }

    /**
     * Verifies that ElapsedTime and ElapsedDuration are included in the output.
     */
    @Test
    public void testElapsedFieldsPresent() throws Exception {
        DynatraceMetric metric = new DynatraceMetric(
                successfulSample, "info", TIMESTAMP_FORMAT, false, false, new HashSet<>(), "jmeter");

        Map<String, Object> result = metric.getMetric(null);

        assertNotNull("ElapsedTime should be present", result.get("ElapsedTime"));
        assertNotNull("ElapsedDuration should be present", result.get("ElapsedDuration"));
    }

    /**
     * Verifies that the "log.source" field is always present and defaults to "jmeter".
     */
    @Test
    public void testLogSourceDefaultValue() throws Exception {
        DynatraceMetric metric = new DynatraceMetric(
                successfulSample, "info", TIMESTAMP_FORMAT, false, false, new HashSet<>(), "jmeter");

        Map<String, Object> result = metric.getMetric(null);

        assertNotNull("log.source must always be present", result.get("log.source"));
        assertEquals("log.source should default to 'jmeter'", "jmeter", result.get("log.source"));
    }

    /**
     * Verifies that the "log.source" field reflects a custom configured value.
     */
    @Test
    public void testLogSourceCustomValue() throws Exception {
        DynatraceMetric metric = new DynatraceMetric(
                successfulSample, "info", TIMESTAMP_FORMAT, false, false, new HashSet<>(), "my-custom-source");

        Map<String, Object> result = metric.getMetric(null);

        assertEquals("log.source should reflect the configured value", "my-custom-source", result.get("log.source"));
    }

    /**
     * Verifies that a blank log.source falls back to "jmeter".
     */
    @Test
    public void testLogSourceFallsBackWhenBlank() throws Exception {
        DynatraceMetric metric = new DynatraceMetric(
                successfulSample, "info", TIMESTAMP_FORMAT, false, false, new HashSet<>(), "  ");

        Map<String, Object> result = metric.getMetric(null);

        assertEquals("log.source should fall back to 'jmeter' when blank", "jmeter", result.get("log.source"));
    }

    /**
     * Verifies that a null log.source falls back to "jmeter".
     */
    @Test
    public void testLogSourceFallsBackWhenNull() throws Exception {
        DynatraceMetric metric = new DynatraceMetric(
                successfulSample, "info", TIMESTAMP_FORMAT, false, false, new HashSet<>(), null);

        Map<String, Object> result = metric.getMetric(null);

        assertEquals("log.source should fall back to 'jmeter' when null", "jmeter", result.get("log.source"));
    }

    @Test
    public void testDefaultParametersExposeSingleTokenOnly() {
        DynatraceBackendClient client = new DynatraceBackendClient();
        Arguments defaults = client.getDefaultParameters();
        Map<String, String> defaultParameters = defaults.getArgumentsAsMap();

        assertTrue("dt.api.token should remain a default parameter", defaultParameters.containsKey("dt.api.token"));
        assertFalse("Deprecated metric toggle should not be exposed in defaults",
                defaultParameters.containsKey(DEPRECATED_METRIC_TOGGLE));
        assertFalse("Deprecated metric token should not be exposed in defaults",
                defaultParameters.containsKey(DEPRECATED_METRIC_TOKEN));
        assertFalse("No separate metric-related parameters should be exposed in defaults",
                defaultParameters.keySet().stream().anyMatch(
                        parameter -> parameter.startsWith("dt.metric") || parameter.startsWith("dt.metrics")));
    }

    @Test
    public void testSetupUsesSingleApiTokenWhenDeprecatedMetricTokenProvided() throws Exception {
        DynatraceBackendClient client = new DynatraceBackendClient();
        BackendListenerContext context = createContext(client,
                Map.of("dt.url", "https://example.test/api/v2/logs/ingest",
                        "dt.api.token", "combined-token",
                        DEPRECATED_METRIC_TOKEN, "deprecated-token"));

        client.setupTest(context);
        try {
            DynatraceMetricSender sender = getSender(client);
            assertEquals("The configured dt.api.token should be used for all ingestion requests",
                    "combined-token", getStringField(sender, "apiToken"));
        } finally {
            client.teardownTest(context);
        }
    }

    @Test
    public void testMetricCollectionRemainsOnWhenDeprecatedToggleIsFalse() throws Exception {
        DynatraceBackendClient client = new DynatraceBackendClient();
        BackendListenerContext context = createContext(client,
                Map.of("dt.url", "https://example.test/api/v2/logs/ingest",
                        "dt.api.token", "combined-token",
                        DEPRECATED_METRIC_TOGGLE, "false"));

        client.setupTest(context);
        try {
            client.handleSampleResults(Arrays.asList(successfulSample), context);

            assertEquals("Samples should still be buffered when a deprecated metric toggle is set to false",
                    1, getSender(client).getListSize());
        } finally {
            getSender(client).clearList();
            client.teardownTest(context);
        }
    }

    private BackendListenerContext createContext(DynatraceBackendClient client, Map<String, String> overrides) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : client.getDefaultParameters().getArgumentsAsMap().entrySet()) {
            parameters.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        parameters.putAll(overrides);
        return new BackendListenerContext(parameters);
    }

    private DynatraceMetricSender getSender(DynatraceBackendClient client) throws Exception {
        Field senderField = DynatraceBackendClient.class.getDeclaredField("sender");
        senderField.setAccessible(true);
        return (DynatraceMetricSender) senderField.get(client);
    }

    private String getStringField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(target);
    }
}
