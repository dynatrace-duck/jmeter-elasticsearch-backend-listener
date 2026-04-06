package io.github.delirius325.jmeter.backendlistener.dynatrace;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.commons.lang3.math.NumberUtils.isCreatable;

public class DynatraceMetric {
    private static final Logger logger = LoggerFactory.getLogger(DynatraceMetric.class);
    private static final String HOSTNAME = solveHostName();
    private static final String ISO_8601_TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

    private SampleResult sampleResult;
    private String dtTestMode;
    private String dtTimestamp;
    private String logSource;
    private HashMap<String, Object> json;
    private Set<String> fields;
    private boolean allReqHeaders;
    private boolean allResHeaders;

    public DynatraceMetric(
            SampleResult sr, String testMode, String timeStamp,
            boolean parseReqHeaders, boolean parseResHeaders, Set<String> fields,
            String logSource) {
        this.sampleResult = sr;
        this.dtTestMode = testMode.trim();
        this.dtTimestamp = timeStamp.trim();
        this.logSource = (logSource != null && !logSource.trim().isEmpty()) ? logSource.trim() : "jmeter";
        this.json = new HashMap<>();
        this.allReqHeaders = parseReqHeaders;
        this.allResHeaders = parseResHeaders;
        this.fields = fields;
    }

    private static String solveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            logger.warn("Could not resolve host name, falling back to localhost", e);
            return "localhost";
        }
    }

    /**
     * This method returns the current metric as a Map(String, Object) for the provided sampleResult.
     * The returned map always contains the Dynatrace-required fields "timestamp" and "content",
     * plus all additional sample attributes (subject to the optional field filter).
     *
     * @param context BackendListenerContext
     * @return a JSON Object as Map(String, Object)
     */
    public Map<String, Object> getMetric(BackendListenerContext context) throws Exception {
        SimpleDateFormat sdf = new SimpleDateFormat(this.dtTimestamp);
        SimpleDateFormat dtSdf = new SimpleDateFormat(ISO_8601_TIMESTAMP_FORMAT);

        // DT-required fields: always present, never filtered out
        this.json.put("timestamp", dtSdf.format(new Date(this.sampleResult.getTimeStamp())));
        this.json.put("content", this.sampleResult.getSampleLabel());
        this.json.put("log.source", this.logSource);

        // Add all default SampleResult parameters
        addFilteredJSON("AllThreads", this.sampleResult.getAllThreads());
        addFilteredJSON("BodySize", this.sampleResult.getBodySizeAsLong());
        addFilteredJSON("Bytes", this.sampleResult.getBytesAsLong());
        addFilteredJSON("SentBytes", this.sampleResult.getSentBytes());
        addFilteredJSON("ConnectTime", this.sampleResult.getConnectTime());
        addFilteredJSON("ContentType", this.sampleResult.getContentType());
        addFilteredJSON("DataType", this.sampleResult.getDataType());
        addFilteredJSON("ErrorCount", this.sampleResult.getErrorCount());
        addFilteredJSON("GrpThreads", this.sampleResult.getGroupThreads());
        addFilteredJSON("IdleTime", this.sampleResult.getIdleTime());
        addFilteredJSON("Latency", this.sampleResult.getLatency());
        addFilteredJSON("ResponseTime", this.sampleResult.getTime());
        addFilteredJSON("SampleCount", this.sampleResult.getSampleCount());
        addFilteredJSON("SampleLabel", this.sampleResult.getSampleLabel());
        addFilteredJSON("ThreadName", this.sampleResult.getThreadName());
        addFilteredJSON("URL", this.sampleResult.getURL());
        addFilteredJSON("ResponseCode", this.sampleResult.getResponseCode());
        addFilteredJSON("TestStartTime", JMeterContextService.getTestStartTime());
        addFilteredJSON("SampleStartTime", sdf.format(new Date(this.sampleResult.getStartTime())));
        addFilteredJSON("SampleEndTime", sdf.format(new Date(this.sampleResult.getEndTime())));
        addFilteredJSON("Timestamp", this.sampleResult.getTimeStamp());
        addFilteredJSON("InjectorHostname", HOSTNAME);

        // Add the details according to the mode that is set
        switch (this.dtTestMode) {
            case "debug":
                addDetails();
                break;
            case "error":
                addDetails();
                break;
            case "info":
                if (!this.sampleResult.isSuccessful())
                    addDetails();
                break;
            default:
                break;
        }

        addAssertions();
        addElapsedTime();
        addElapsedDuration();
        addCustomFields(context);
        parseHeadersAsJsonProps(this.allReqHeaders, this.allResHeaders);

        return this.json;
    }

    /**
     * This method adds all the assertions for the current sampleResult
     */
    private void addAssertions() {
        AssertionResult[] assertionResults = this.sampleResult.getAssertionResults();
        if (assertionResults != null) {
            Map<String, Object>[] assertionArray = new HashMap[assertionResults.length];
            Integer i = 0;
            String failureMessage = "";
            boolean isFailure = false;
            for (AssertionResult assertionResult : assertionResults) {
                HashMap<String, Object> assertionMap = new HashMap<>();
                boolean failure = assertionResult.isFailure() || assertionResult.isError();
                isFailure = isFailure || assertionResult.isFailure() || assertionResult.isError();
                assertionMap.put("failure", failure);
                assertionMap.put("failureMessage", assertionResult.getFailureMessage());
                failureMessage += assertionResult.getFailureMessage() + "\n";
                assertionMap.put("name", assertionResult.getName());
                assertionArray[i] = assertionMap;
                i++;
            }
            addFilteredJSON("AssertionResults", assertionArray);
            addFilteredJSON("FailureMessage", failureMessage);
            addFilteredJSON("Success", !isFailure);
        }
    }

    /**
     * Adds the ElapsedTime (ms since test start) to the metric.
     */
    private void addElapsedTime() {
        long elapsed = System.currentTimeMillis() - JMeterContextService.getTestStartTime();
        addFilteredJSON("ElapsedTime", elapsed);
    }

    /**
     * Adds the ElapsedDuration in seconds since test start.
     */
    private void addElapsedDuration() {
        long elapsed = (System.currentTimeMillis() - JMeterContextService.getTestStartTime()) / 1000;
        addFilteredJSON("ElapsedDuration", elapsed);
    }

    /**
     * Adds all custom fields added by the user in the Backend Listener's GUI panel.
     * Parameters starting with "dt." are treated as plugin configuration and are excluded.
     *
     * @param context BackendListenerContext
     */
    private void addCustomFields(BackendListenerContext context) {
        if (context == null) {
            return;
        }
        Iterator<String> pluginParameters = context.getParameterNamesIterator();
        String parameter;
        while (pluginParameters.hasNext()) {
            String parameterName = pluginParameters.next();

            if (!parameterName.startsWith("dt.") && context.containsParameter(parameterName)
                    && !"".equals(parameter = context.getParameter(parameterName).trim())) {
                if (isCreatable(parameter)) {
                    addFilteredJSON(parameterName, Long.parseLong(parameter));
                } else {
                    addFilteredJSON(parameterName, parameter);
                }
            }
        }
    }

    /**
     * Adds the request and response body/headers.
     */
    private void addDetails() {
        addFilteredJSON("RequestHeaders", this.sampleResult.getRequestHeaders());
        addFilteredJSON("RequestBody", this.sampleResult.getSamplerData());
        addFilteredJSON("ResponseHeaders", this.sampleResult.getResponseHeaders());
        addFilteredJSON("ResponseBody", this.sampleResult.getResponseDataAsString());
        addFilteredJSON("ResponseMessage", this.sampleResult.getResponseMessage());
    }

    /**
     * Parses request and/or response headers into individual JSON properties when the
     * corresponding parse flags are enabled.
     *
     * @param allReqHeaders when true, all request headers are added as individual JSON properties
     * @param allResHeaders when true, all response headers are added as individual JSON properties
     */
    private void parseHeadersAsJsonProps(boolean allReqHeaders, boolean allResHeaders) {
        LinkedList<String[]> headersArrayList = new LinkedList<String[]>();

        if (allReqHeaders) {
            headersArrayList.add(this.sampleResult.getRequestHeaders().split("\n"));
        }

        if (allResHeaders) {
            headersArrayList.add(this.sampleResult.getResponseHeaders().split("\n"));
        }

        for (String[] lines : headersArrayList) {
            for (int i = 0; i < lines.length; i++) {
                String[] header = lines[i].split(":", 2);
                if (header.length > 1) {
                    this.json.put(header[0].trim(), header[1].trim());
                }
            }
        }
    }

    /**
     * Adds a given key-value pair to the JSON map if the key is contained in the field filter,
     * or if the field filter is empty (meaning all fields are included).
     *
     * @param key   field name
     * @param value field value
     */
    private void addFilteredJSON(String key, Object value) {
        if (this.fields.size() == 0 || this.fields.contains(key.toLowerCase())) {
            this.json.put(key, value);
        }
    }
}
