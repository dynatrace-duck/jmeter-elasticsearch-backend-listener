package io.github.delirius325.jmeter.backendlistener.dynatrace;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

public class DynatraceBackendClient extends AbstractBackendListenerClient {

    private static final String DT_URL = "dt.url";
    private static final String DT_API_TOKEN = "dt.api.token";
    private static final String DT_FIELDS = "dt.fields";
    private static final String DT_TIMESTAMP = "dt.timestamp";
    private static final String DT_BATCH_SIZE = "dt.batch.size";
    private static final String DT_TIMEOUT_MS = "dt.timeout.ms";
    private static final String DT_SAMPLE_FILTER = "dt.sample.filter";
    private static final String DT_TEST_MODE = "dt.test.mode";
    private static final String DT_PARSE_REQ_HEADERS = "dt.parse.all.req.headers";
    private static final String DT_PARSE_RES_HEADERS = "dt.parse.all.res.headers";
    private static final String DT_LOG_SOURCE = "dt.log.source";
    private static final long DEFAULT_TIMEOUT_MS = 10000L;
    private static final Logger logger = LoggerFactory.getLogger(DynatraceBackendClient.class);
    private static final Map<String, String> DEFAULT_ARGS = new LinkedHashMap<>();

    static {
        DEFAULT_ARGS.put(DT_URL, "https://<env-id>.live.dynatrace.com/api/v2/logs/ingest");
        DEFAULT_ARGS.put(DT_API_TOKEN, "");
        DEFAULT_ARGS.put(DT_TIMESTAMP, "yyyy-MM-dd'T'HH:mm:ss.SSSZZ");
        DEFAULT_ARGS.put(DT_BATCH_SIZE, "100");
        DEFAULT_ARGS.put(DT_TIMEOUT_MS, Long.toString(DEFAULT_TIMEOUT_MS));
        DEFAULT_ARGS.put(DT_SAMPLE_FILTER, null);
        DEFAULT_ARGS.put(DT_FIELDS, null);
        DEFAULT_ARGS.put(DT_TEST_MODE, "info");
        DEFAULT_ARGS.put(DT_PARSE_REQ_HEADERS, "false");
        DEFAULT_ARGS.put(DT_PARSE_RES_HEADERS, "false");
        DEFAULT_ARGS.put(DT_LOG_SOURCE, "jmeter");
    }

    private DynatraceMetricSender sender;
    private Set<String> modes;
    private Set<String> filters;
    private Set<String> fields;
    private int bulkSize;
    private long timeoutMs;

    @Override
    public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();
        DEFAULT_ARGS.forEach(arguments::addArgument);
        return arguments;
    }

    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        try {
            this.filters = new HashSet<>();
            this.fields = new HashSet<>();
            this.modes = new HashSet<>(Arrays.asList("info", "debug", "error", "quiet"));
            this.bulkSize = Integer.parseInt(context.getParameter(DT_BATCH_SIZE));
            this.timeoutMs = Long.parseLong(context.getParameter(DT_TIMEOUT_MS));

            convertParameterToSet(context, DT_SAMPLE_FILTER, this.filters);
            convertParameterToSet(context, DT_FIELDS, this.fields);

            this.sender = new DynatraceMetricSender(
                    context.getParameter(DT_URL),
                    context.getParameter(DT_API_TOKEN),
                    (int) this.timeoutMs);

            checkTestMode(context.getParameter(DT_TEST_MODE));
            super.setupTest(context);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialise the Dynatrace Backend Listener", e);
        }
    }

    @Override
    public void handleSampleResults(List<SampleResult> results, BackendListenerContext context) {
        for (SampleResult sr : results) {
            DynatraceMetric metric = new DynatraceMetric(sr, context.getParameter(DT_TEST_MODE),
                    context.getParameter(DT_TIMESTAMP),
                    context.getBooleanParameter(DT_PARSE_REQ_HEADERS, false),
                    context.getBooleanParameter(DT_PARSE_RES_HEADERS, false), fields,
                    context.getParameter(DT_LOG_SOURCE));

            if (validateSample(context, sr)) {
                try {
                    this.sender.addToList(new Gson().toJson(metric.getMetric(context)));
                } catch (Exception e) {
                    logger.error(
                            "The Dynatrace Backend Listener was unable to add sampler to the list of samplers to send.");
                    e.printStackTrace();
                }
            }
        }

        if (this.sender.getListSize() >= this.bulkSize) {
            try {
                this.sender.sendRequest();
            } catch (Exception e) {
                logger.error("Error occurred while sending batch request to Dynatrace.", e);
            } finally {
                this.sender.clearList();
            }
        }
    }

    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        if (this.sender.getListSize() > 0) {
            this.sender.sendRequest();
        }
        this.sender.close();
        super.teardownTest(context);
    }

    /**
     * Converts a semicolon-separated parameter value into a Set of trimmed, lower-cased strings.
     *
     * @param context   the BackendListenerContext
     * @param parameter the parameter name to read
     * @param set       the Set to populate
     */
    private void convertParameterToSet(BackendListenerContext context, String parameter, Set<String> set) {
        String[] array = (context.getParameter(parameter).contains(";")) ? context.getParameter(parameter).split(";")
                : new String[] { context.getParameter(parameter) };
        if (array.length > 0 && !array[0].trim().equals("")) {
            for (String entry : array) {
                set.add(entry.toLowerCase().trim());
                if (logger.isDebugEnabled())
                    logger.debug("Parsed from " + parameter + ": " + entry.toLowerCase().trim());
            }
        }
    }

    /**
     * Validates whether the current sample should be forwarded to Dynatrace based on
     * the configured sample filters and test mode.
     *
     * @param context the BackendListenerContext
     * @param sr      the current SampleResult
     * @return {@code true} if the sample should be sent
     */
    private boolean validateSample(BackendListenerContext context, SampleResult sr) {
        boolean valid = true;
        String sampleLabel = sr.getSampleLabel().toLowerCase().trim();

        if (this.filters.size() > 0) {
            for (String filter : filters) {
                Pattern pattern = Pattern.compile(filter);
                Matcher matcher = pattern.matcher(sampleLabel);

                if (!sampleLabel.startsWith("!!") && (sampleLabel.contains(filter) || matcher.find())) {
                    valid = true;
                    break;
                } else {
                    valid = false;
                }
            }
        }

        // If the sample is successful but test mode is "error", drop it
        if (sr.isSuccessful() && context.getParameter(DT_TEST_MODE).trim().equalsIgnoreCase("error") && valid) {
            valid = false;
        }

        return valid;
    }

    /**
     * Warns the user if the configured test mode is not one of the recognised values.
     *
     * @param mode the dt.test.mode parameter value
     */
    private void checkTestMode(String mode) {
        if (!this.modes.contains(mode)) {
            logger.warn(
                    "The parameter \"dt.test.mode\" is not set to a recognised value. Valid modes are: debug, info, error, quiet.");
            logger.warn(
                    " -- \"debug\": sends request and response details for every sample.");
            logger.warn(
                    " -- \"info\": sends request and response details only for failed samples. Recommended for most environments.");
            logger.warn(
                    " -- \"error\": only forwards failed samples (successful samples are dropped).");
            logger.warn(
                    " -- \"quiet\": never sends request/response details.");
        }
    }
}
