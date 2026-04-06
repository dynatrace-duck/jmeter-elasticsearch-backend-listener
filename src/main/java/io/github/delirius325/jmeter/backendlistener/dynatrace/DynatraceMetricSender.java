package io.github.delirius325.jmeter.backendlistener.dynatrace;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DynatraceMetricSender implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(DynatraceMetricSender.class);

    /**
     * Dynatrace Log Ingest v2 enforces a 5 MB per-request limit. We use 4 MB as a
     * conservative threshold to leave room for JSON array framing overhead.
     */
    private static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;

    /**
     * Dynatrace Log Ingest v2 enforces a maximum of 10,000 log records per request.
     * We use 9,000 as a conservative threshold.
     */
    private static final int MAX_LOG_RECORDS = 9000;

    private final String dtUrl;
    private final String apiToken;
    private final List<String> metricList;
    private final CloseableHttpClient httpClient;

    public DynatraceMetricSender(String dtUrl, String apiToken, int timeoutMs) {
        this.dtUrl = dtUrl;
        this.apiToken = apiToken;
        this.metricList = new LinkedList<>();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setSocketTimeout(timeoutMs)
                .build();
        this.httpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    /**
     * Returns the current number of buffered metric documents.
     *
     * @return size of the metric buffer
     */
    public int getListSize() {
        return this.metricList.size();
    }

    /**
     * Clears all buffered metric documents.
     */
    public void clearList() {
        this.metricList.clear();
    }

    /**
     * Adds a metric JSON document string to the buffer.
     *
     * @param metric JSON string representing a single log event
     */
    public void addToList(String metric) {
        this.metricList.add(metric);
    }

    /**
     * Sends all buffered metric documents to the Dynatrace Log Ingest v2 endpoint.
     * If the serialized payload would exceed the 4 MB threshold or 9,000 records, it
     * is automatically split into multiple requests.
     *
     * @throws IOException if an HTTP-level error occurs
     */
    public void sendRequest() throws IOException {
        if (this.metricList.isEmpty()) {
            return;
        }

        List<List<String>> chunks = splitIntoChunks();
        for (List<String> chunk : chunks) {
            sendPayload(buildJsonArray(chunk), chunk.size());
        }
    }

    /**
     * Closes the underlying HTTP client and releases all resources.
     */
    @Override
    public void close() throws IOException {
        this.httpClient.close();
    }

    /**
     * Splits the metric buffer into chunks that each stay within the payload-size
     * and record-count limits imposed by the Dynatrace Log Ingest v2 API.
     */
    private List<List<String>> splitIntoChunks() {
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        // Account for the two characters used by the surrounding JSON array brackets "[]"
        int currentBytes = 2;

        for (String metric : this.metricList) {
            // +1 for the comma separator between elements
            int metricBytes = metric.getBytes(StandardCharsets.UTF_8).length + 1;
            if (!current.isEmpty()
                    && (currentBytes + metricBytes > MAX_PAYLOAD_BYTES || current.size() >= MAX_LOG_RECORDS)) {
                chunks.add(current);
                current = new ArrayList<>();
                currentBytes = 2;
            }
            current.add(metric);
            currentBytes += metricBytes;
        }

        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    /**
     * Serialises a list of metric JSON strings into a JSON array string.
     */
    private String buildJsonArray(List<String> metrics) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < metrics.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(metrics.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * POSTs a JSON array payload to the Dynatrace Log Ingest v2 endpoint.
     *
     * @param body       serialised JSON array
     * @param recordCount number of log records in this payload (used for logging)
     * @throws IOException if an I/O error occurs during the request
     */
    private void sendPayload(String body, int recordCount) throws IOException {
        HttpPost post = new HttpPost(this.dtUrl);
        post.setHeader("Authorization", "Api-Token " + this.apiToken);
        post.setHeader("Content-Type", "application/json; charset=utf-8");
        post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

        try (CloseableHttpResponse response = this.httpClient.execute(post)) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == HttpStatus.SC_NO_CONTENT || statusCode == HttpStatus.SC_OK) {
                logger.debug("Dynatrace Backend Listener successfully ingested {} log records.", recordCount);
            } else {
                logger.error("Dynatrace Backend Listener failed to ingest log records. Response status: {}",
                        response.getStatusLine().toString());
            }
        }
    }
}
