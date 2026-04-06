# JMeter Dynatrace Backend Listener

## Overview
### Description
JMeter Dynatrace Backend Listener is a JMeter plugin that sends test results to Dynatrace via the [Log Ingest v2 API](https://www.dynatrace.com/support/help/dynatrace-api/environment-api/log-monitoring/ingest-logs). It is an alternative live-monitoring tool to the built-in "InfluxDB" backend listener.

### Features

* Sends JMeter sample results as structured JSON log events to Dynatrace
  * All standard SampleResult fields are automatically parsed by Dynatrace on ingest
* API Token authentication
* Batched requests with automatic payload splitting
  * Requests are capped at 4 MB / 9,000 records to stay within Dynatrace Log Ingest v2 limits
* Sample filters
  * Only send the samples you want using `dt.sample.filter`: `filter1;filter2;filter3`
  * Exclude specific samplers: `!!exclude_this;filter1;filter2`
* Field filters
  * Restrict the fields sent to Dynatrace using `dt.fields`: `field1;field2;field3`
  * Available fields:
    * AllThreads, BodySize, Bytes, SentBytes, ConnectTime, ContentType
    * DataType, ErrorCount, GrpThreads, IdleTime, Latency, ResponseTime
    * SampleCount, SampleLabel, ThreadName, URL, ResponseCode
    * TestStartTime, SampleStartTime, SampleEndTime, Timestamp
    * InjectorHostname, ElapsedTime, ElapsedDuration
    * AssertionResults, FailureMessage, Success *(when assertions are present)*
    * RequestHeaders, RequestBody, ResponseHeaders, ResponseBody, ResponseMessage *(mode-dependent)*
* Four logging modes
  * __debug__ – sends request/response details (headers, body) for every sample
  * __info__ – sends all samples, but includes request/response details only for failed samples *(recommended for most environments)*
  * __error__ – only forwards failed samples (along with their request/response details)
  * __quiet__ – sends metrics only; never includes request/response details

### Configuration Parameters

| Parameter | Default | Description |
|---|---|---|
| `dt.url` | `https://<env-id>.live.dynatrace.com/api/v2/logs/ingest` | Full Dynatrace Log Ingest v2 endpoint URL |
| `dt.api.token` | *(empty)* | Dynatrace API token with `logs.ingest` scope |
| `dt.timestamp` | `yyyy-MM-dd'T'HH:mm:ss.SSSZZ` | Format for `SampleStartTime` / `SampleEndTime` attributes |
| `dt.batch.size` | `100` | Number of samples to accumulate before sending a batch |
| `dt.timeout.ms` | `10000` | Socket timeout in milliseconds |
| `dt.sample.filter` | *(empty)* | Semicolon-separated list of sample label filters |
| `dt.fields` | *(empty)* | Semicolon-separated list of fields to include (empty = all) |
| `dt.test.mode` | `info` | Logging mode: `debug`, `info`, `error`, or `quiet` |
| `dt.parse.all.req.headers` | `false` | Expand all request headers as individual log attributes |
| `dt.parse.all.res.headers` | `false` | Expand all response headers as individual log attributes |

Every log event sent to Dynatrace always contains a `timestamp` (ISO 8601 sample time) and a `content` (the sample label) field in addition to the fields listed above.

### Listener class name
```
io.github.delirius325.jmeter.backendlistener.dynatrace.DynatraceBackendClient
```

### Maven
```xml
<dependency>
  <groupId>io.github.delirius325</groupId>
  <artifactId>jmeter.backendlistener.dynatrace</artifactId>
  <version>3.0.0</version>
</dependency>
```

## Contributing
Feel free to contribute by branching and making pull requests, or simply by suggesting ideas through the "Issues" tab.

### Packaging and testing your newly added code
Execute the command below. Make sure `JAVA_HOME` is set properly.
```
mvn package
```
Move the resulting JAR to your `JMETER_HOME/lib/ext`.

