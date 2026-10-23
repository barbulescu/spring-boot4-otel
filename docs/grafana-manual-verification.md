# Checking logs, metrics and traces in Grafana by hand

This guide shows how to confirm by hand that each signal reaches Grafana, and that traces and logs link to each other.

## 1. Start the stack and the application

```bash
# Option A: Testcontainers on Podman
./mvnw spring-boot:test-run
# Look for this line in the console and open the URL:
#   Grafana is available at http://localhost:NNNNN (user: admin, password: admin)

# Option B: podman compose. Grafana at http://localhost:3000
podman compose up -d
./mvnw spring-boot:run
```

Grafana starts with anonymous admin access, so no login is needed (if prompted, use `admin` / `admin`).

Under **Connections → Data sources** there are four pre-provisioned datasources: **Loki**, **Prometheus**, **Tempo** and Pyroscope.

## 2. Generate traffic and note a trace ID

```bash
for i in $(seq 1 20); do curl -s "http://localhost:8080/greeting?name=user$i" > /dev/null; done
curl -s "http://localhost:8080/greeting?name=manual-check"
# {"message":"Hello, manual-check!","traceId":"76dd316e59b9633f0ab7ffc07209cb7b"}
```

Copy the `traceId`; you'll use it below. Wait about 10–15 seconds: metrics are pushed every 10s, and logs and spans are sent in batches every few seconds.

---

## 3. Traces (Tempo)

1. Open **Explore** (compass icon) and pick the **Tempo** datasource.
2. **Find the trace by ID**: choose the **TraceQL** query type, paste the trace ID
   (e.g. `76dd316e59b9633f0ab7ffc07209cb7b`) and press **Run query**.
3. **Or search**: choose the **Search** query type, set *Service Name* = `spring-boot4-otel` and
   *Span Name* = `http get /greeting`, or enter this TraceQL:

   ```traceql
   {resource.service.name="spring-boot4-otel" && name="http get /greeting"}
   ```

**What to expect**

- A trace with root span `http get /greeting`, kind `server`.
- Span attributes: `method=GET`, `uri=/greeting`, `status=200`, `outcome=SUCCESS`.
- Resource attributes: `service.name=spring-boot4-otel`, `deployment.environment.name=local`,
  `telemetry.sdk.name=opentelemetry`.

## 4. Logs (Loki)

1. In **Explore**, pick the **Loki** datasource and switch the editor to **Code**.
2. All application logs:

   ```logql
   {service_name="spring-boot4-otel"}
   ```

3. Only the greeting logs:

   ```logql
   {service_name="spring-boot4-otel"} |= "Greeting"
   ```

4. Logs for one request (replace the trace ID):

   ```logql
   {service_name="spring-boot4-otel"} | trace_id="76dd316e59b9633f0ab7ffc07209cb7b"
   ```

**What to expect**

- Lines such as `Greeting manual-check` at severity `INFO`.
- Expanding a line shows the fields `trace_id`, `span_id`, `severity_text`, `scope_name`
  (`com.example.otel.GreetingController`), `thread_name` (from the appender's experimental attributes) and `detected_level`.
- Spring Boot startup logs (e.g. `Tomcat started on port 8080`) also appear. They have no `trace_id`.

> **Logs drilldown**: **Drilldown → Logs** in the main menu lists `spring-boot4-otel` as a service, with log volume by level.
> No query is needed there.

## 5. Metrics (Prometheus)

1. In **Explore**, pick the **Prometheus** datasource and switch the editor to **Code**.
2. Run these queries:

| What | PromQL |
|------|--------|
| Custom counter (one increment per greeting) | `greetings_total{job="spring-boot4-otel"}` |
| Request rate per endpoint | `sum by (uri) (rate(http_server_requests_milliseconds_count{job="spring-boot4-otel"}[1m]))` |
| p95 latency per endpoint (ms) | `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_milliseconds_bucket{job="spring-boot4-otel"}[5m])))` |
| Heap used | `sum(jvm_memory_used_bytes{job="spring-boot4-otel", area="heap"})` |
| Resource attributes of the app | `target_info{job="spring-boot4-otel"}` |

**What to expect**

- `greetings_total` matches the number of `/greeting` calls you made (21 after step 2).
- How names are translated from Micrometer to Prometheus:
  - The OTel `service.name` resource attribute becomes the `job` and `service_name` labels.
  - Dots become underscores.
  - Counters get the `_total` suffix.
  - Micrometer timers are exported in milliseconds, so the names end in `_milliseconds_*`.
- Use **Metrics browser** (or **Drilldown → Metrics**) to list everything the app exports: `http_server_requests_*`,
  `jvm_*`, `process_*`, `system_*`, `logback_events_total`, `tomcat_*`, and others.

> **Dashboards**: **Dashboards** has *RED Metrics (classic histogram)* and *JVM Overview (OpenTelemetry)*
> pre-installed. They were written for the OpenTelemetry Java agent's metric names, so some panels may stay empty for
> Micrometer metrics. Explore is the reliable way to check.

## 6. Correlation: trace ↔ logs

The LGTM datasources are pre-configured to link the two:

- **Trace → logs**: open a trace in Tempo (step 3), click a span and press the **Logs for this span** button.
  Grafana runs `{service_name="spring-boot4-otel"} | trace_id="<trace id>"` in Loki and shows the matching
  `Greeting …` line.
- **Logs → trace**: in Loki (step 4), expand a `Greeting …` line. Next to the `trace_id` field there is a
  **Trace: &lt;id&gt;** link (a Loki *derived field*) that opens the trace in Tempo.

Use **Split** in Explore to keep Loki and Tempo side by side.

## Troubleshooting

| Symptom | Likely cause / fix |
|---------|-------------------|
| No traces at all | Make sure `management.tracing.sampling.probability=1.0` is set (the default samples only 10%). |
| Metrics show up late | Metrics are pushed every `management.otlp.metrics.export.step` (10s here; the default is 1m). |
| No logs in Loki, but traces/metrics are fine | Check that `logback-spring.xml` includes the `OTEL` appender and that `OpenTelemetryAppenderInstaller` runs. Without `OpenTelemetryAppender.install(...)` the appender silently drops events. |
| p95 query returns `NaN` | Needs `management.metrics.distribution.percentiles-histogram.http.server.requests=true` and at least two scrapes' worth of traffic. |
| `spring-boot:run` logs OTLP export errors (connection refused) | The LGTM stack isn't running on ports 4318/3000. Start it with `podman compose up -d`, or use `./mvnw spring-boot:test-run`. |
| `podman compose` fails with `looking up compose provider failed` | Install a provider (`brew install podman-compose`), or use the `podman run` command from the README. |
| Tests fail with `Could not find a valid Docker environment` | Testcontainers can't reach Podman. Run `podman machine start` and set `DOCKER_HOST` as described in the README (*Pointing Testcontainers at Podman*). |
| In tests, metrics/traces are missing but logs arrive | Spring Boot disables metrics/tracing export in tests; add `@AutoConfigureMetrics` and `@AutoConfigureTracing`. |
