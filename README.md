# JMeter Dynatrace Backend Listener

## Overview

### Description
JMeter Dynatrace Backend Listener is a JMeter plugin that streams load-test results to **Dynatrace** in real time via the [Log Ingest v2 API](https://www.dynatrace.com/support/help/dynatrace-api/environment-api/log-monitoring/ingest-logs). Each JMeter sample is forwarded as a structured JSON log event, making the full result set immediately searchable in the Dynatrace Log Viewer without any additional pipeline or index setup.

This plugin is a drop-in alternative to JMeter's built-in InfluxDB backend listener and is a complete rewrite of the earlier Elasticsearch-based implementation.

### Features

* **Structured log events** – every JMeter `SampleResult` is serialised as a JSON object and pushed to the Dynatrace Log Ingest v2 API
* **API Token authentication** – a single `Authorization: Api-Token <token>` header secures all requests
* **Batched requests with automatic payload splitting** – the buffer is flushed when it reaches the configured `dt.batch.size`; oversized payloads are automatically split to stay within the Dynatrace limits of 5 MB / 10,000 records per request (conservative thresholds of 4 MB and 9,000 records are used)
* **Sample filters** – control exactly which samplers are forwarded
  * Include by label substring or regex: `filter1;filter2;filter3`
  * Exclude specific samplers by prefixing the filter with `!!`: `!!exclude_this`
* **Field filters** – restrict which attributes are sent using `dt.fields`; leave empty to send all fields
* **Four logging modes** that control when request/response bodies and headers are included
  * `debug` – request/response details sent for every sample
  * `info` – details sent only for failed samples *(recommended)*
  * `error` – failed samples only (successful samples are dropped entirely)
  * `quiet` – metrics only; request/response details are never included
* **Custom fields** – add extra key-value pairs directly in the Backend Listener GUI; any parameter whose name does not start with `dt.` is forwarded as a log attribute
* **Configurable log source** – tag every event with a `log.source` attribute (default: `jmeter`) to distinguish load-test traffic from other log sources in Dynatrace

---

## Prerequisites

| Requirement | Minimum version |
|---|---|
| Java (JDK) | 11 |
| Apache Maven | 3.6 |
| Apache JMeter | 5.3 |
| Dynatrace environment | SaaS or Managed with Log Monitoring enabled |

---

## Building from Source

1. Clone the repository and make sure `JAVA_HOME` points to a JDK 11+ installation.

2. Run the Maven package goal:

   ```bash
   mvn package
   ```

   This produces two artefacts inside the `target/` directory:

   | File | Purpose |
   |---|---|
   | `jmeter.backendlistener.dynatrace-3.0.1.jar` | Shaded JAR (includes the Gson dependency) – **use this one** |
   | `jmeter.backendlistener.dynatrace-3.0.1-original.jar` | Un-shaded JAR (for reference only) |

3. To run the unit tests as part of the build:

   ```bash
   mvn verify
   ```

---

## Installing into JMeter

1. Copy the shaded JAR (`jmeter.backendlistener.dynatrace-<version>.jar`) into your JMeter `lib/ext/` directory:

   ```
   $JMETER_HOME/lib/ext/jmeter.backendlistener.dynatrace-3.0.1.jar
   ```

2. Restart JMeter if it is already running.

---

## Dynatrace Setup

### 1. Create an API Token

1. In your Dynatrace environment navigate to **Settings → Access Tokens → Generate new token**.
2. Give the token a name (e.g. `jmeter-ingest`).
3. Enable the scope **`logs.ingest`** (under *Log Monitoring*).
4. Copy the generated token – you will need it in the next step.

### 2. Find your Log Ingest Endpoint

The endpoint follows the pattern below. Replace `<env-id>` with your Dynatrace Environment ID (visible in the browser URL when you are logged in):

```
https://<env-id>.live.dynatrace.com/api/v2/logs/ingest
```

For Managed deployments the URL uses your own domain:

```
https://<your-domain>/e/<env-id>/api/v2/logs/ingest
```

---

## Adding the Listener to a JMeter Test Plan

1. Right-click on your **Thread Group** (or the test plan root) and choose **Add → Listener → Backend Listener**.
2. In the **Backend Listener implementation** dropdown select:
   ```
   io.github.delirius325.jmeter.backendlistener.dynatrace.DynatraceBackendClient
   ```
3. Fill in the parameters described in the table below. At a minimum you must set `dt.url` and `dt.api.token`.

---

## Configuration Parameters

| Parameter | Default | Description |
|---|---|---|
| `dt.url` | `https://<env-id>.live.dynatrace.com/api/v2/logs/ingest` | Full Dynatrace Log Ingest v2 endpoint URL |
| `dt.api.token` | *(empty)* | Dynatrace API token with `logs.ingest` scope |
| `dt.timestamp` | `yyyy-MM-dd'T'HH:mm:ss.SSSZZ` | `SimpleDateFormat` pattern used for `SampleStartTime` / `SampleEndTime` attributes |
| `dt.batch.size` | `100` | Number of samples to buffer before flushing to Dynatrace |
| `dt.timeout.ms` | `10000` | HTTP socket timeout in milliseconds |
| `dt.sample.filter` | *(empty)* | Semicolon-separated list of sample label filters (substring or regex). Prefix a value with `!!` to exclude that sampler. |
| `dt.fields` | *(empty)* | Semicolon-separated list of field names to include. Leave empty to include all fields. |
| `dt.test.mode` | `info` | Logging mode: `debug`, `info`, `error`, or `quiet` (see *Features* above) |
| `dt.parse.all.req.headers` | `false` | When `true`, all request headers are expanded into individual log attributes |
| `dt.parse.all.res.headers` | `false` | When `true`, all response headers are expanded into individual log attributes |
| `dt.log.source` | `jmeter` | Value written to the `log.source` attribute on every event |

> **Custom fields:** any parameter you add in the GUI that does *not* start with `dt.` is forwarded as an extra log attribute. Numeric values are stored as numbers; everything else is stored as a string.

Every log event always includes the following Dynatrace-required fields regardless of any `dt.fields` filter:

| Field | Description |
|---|---|
| `timestamp` | ISO 8601 sample timestamp (used by Dynatrace to place the event on the timeline) |
| `content` | Sample label (the primary log message shown in the Log Viewer) |
| `log.source` | Identifies the origin of the log record (default: `jmeter`) |

---

## Log Event Fields Reference

The table below lists all optional fields that can appear on a log event.

| Field name | Type | Description |
|---|---|---|
| `AllThreads` | number | Total active threads across all thread groups at sample time |
| `BodySize` | number | Response body size in bytes |
| `Bytes` | number | Total bytes received |
| `SentBytes` | number | Bytes sent with the request |
| `ConnectTime` | number | Time to establish the connection (ms) |
| `ContentType` | string | Response content-type header |
| `DataType` | string | JMeter data type |
| `ErrorCount` | number | Number of errors in this sample |
| `GrpThreads` | number | Active threads in this thread group |
| `IdleTime` | number | Idle time (ms) |
| `Latency` | number | Time to first byte (ms) |
| `ResponseTime` | number | Total elapsed time (ms) |
| `SampleCount` | number | Number of samples in this result |
| `SampleLabel` | string | Sampler name / label |
| `ThreadName` | string | JMeter thread name |
| `URL` | string | Target URL |
| `ResponseCode` | string | HTTP response code |
| `TestStartTime` | number | Test start epoch time (ms) |
| `SampleStartTime` | string | Sample start time (formatted with `dt.timestamp`) |
| `SampleEndTime` | string | Sample end time (formatted with `dt.timestamp`) |
| `Timestamp` | number | Sample epoch timestamp (ms) |
| `InjectorHostname` | string | Hostname of the JMeter injector |
| `ElapsedTime` | number | Milliseconds since test start |
| `ElapsedDuration` | number | Seconds since test start |
| `AssertionResults` | array | Array of assertion objects (`name`, `failure`, `failureMessage`) – only present when assertions are configured |
| `FailureMessage` | string | Concatenated assertion failure messages – only present when assertions are configured |
| `Success` | boolean | `false` if any assertion failed – only present when assertions are configured |
| `RequestHeaders` | string | Raw request headers *(mode-dependent)* |
| `RequestBody` | string | Request body *(mode-dependent)* |
| `ResponseHeaders` | string | Raw response headers *(mode-dependent)* |
| `ResponseBody` | string | Response body *(mode-dependent)* |
| `ResponseMessage` | string | HTTP reason phrase *(mode-dependent)* |

---

## Finding Your Data in Dynatrace

Once the test is running, log events begin appearing in Dynatrace within seconds.

### Log Viewer

1. Navigate to **Observe & Explore → Logs** (or search for *Logs* in the navigation bar).
2. Use the search bar to filter by `log.source = "jmeter"` to isolate JMeter events from other log sources.

### Useful queries

```
log.source = "jmeter"
```
*Show all JMeter log events.*

```
log.source = "jmeter" AND ResponseCode != "200"
```
*Show all non-200 samples.*

```
log.source = "jmeter" AND content = "HTTP GET /api/health"
```
*Show events for a specific sampler (the `content` field holds the sample label).*

```
log.source = "jmeter" AND ResponseTime > 2000
```
*Show samples where the response time exceeded 2 seconds.*

### Building dashboards

All log attributes (e.g. `ResponseTime`, `ResponseCode`, `SampleLabel`, `ElapsedDuration`) are available as facets in Notebooks and Dashboard tiles. Use the **Log & event stream** or **Metric** visualization tiles and group/filter by any attribute from the [field reference](#log-event-fields-reference) above.

---

## Maven Dependency

```xml
<dependency>
  <groupId>io.github.delirius325</groupId>
  <artifactId>jmeter.backendlistener.dynatrace</artifactId>
  <version>3.0.1</version>
</dependency>
```

---

## Contributing
Feel free to contribute by branching and making pull requests, or simply by suggesting ideas through the "Issues" tab.

### Packaging and testing your changes
Make sure `JAVA_HOME` points to JDK 11 or later, then run:

```bash
mvn verify
```

Copy the resulting shaded JAR from `target/` to `$JMETER_HOME/lib/ext/` and restart JMeter to test your changes end-to-end.

