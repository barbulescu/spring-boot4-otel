# spring-boot4-otel

A Spring Boot **4.1.2** application (Java 25) that exports **logs, metrics and traces** over
OpenTelemetry (OTLP) to the [Grafana LGTM stack](https://github.com/grafana/docker-otel-lgtm)
(Loki, Grafana, Tempo, Prometheus + OpenTelemetry Collector). The tests use Testcontainers to check all three signals.

```
                         OTLP/HTTP (4318)
 ┌────────────────────┐  logs    ┌─────────────────────── grafana/otel-lgtm ───────────────────────┐
 │ spring-boot4-otel  │ ───────▶ │ OTel Collector ──▶ Loki (logs)                                  │
 │                    │  metrics │                ──▶ Prometheus (metrics)  ──▶ Grafana :3000      │
 │ GET /greeting      │ ───────▶ │                ──▶ Tempo (traces)                               │
 │                    │  traces  │                                                                 │
 └────────────────────┘ ───────▶ └─────────────────────────────────────────────────────────────────┘
```

## How it is wired

| Signal  | Produced by | Exported by | Stored in |
|---------|-------------|-------------|-----------|
| Traces  | Micrometer Observation → Micrometer Tracing (OTel bridge) | `spring-boot-starter-opentelemetry` (OTLP span exporter) | Tempo |
| Metrics | Micrometer (`http.server.requests`, JVM metrics, custom `greetings` counter) | `micrometer-registry-otlp` | Prometheus |
| Logs    | SLF4J/Logback → `OpenTelemetryAppender` ([`logback-spring.xml`](src/main/resources/logback-spring.xml)) | OTel SDK OTLP log exporter | Loki |

The pieces that are not auto-configured:

- **Logs**: Spring Boot configures the OTLP log *exporter*, but you have to bridge Logback to it yourself.
  This project adds `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`, declares the
  `OTEL` appender in `logback-spring.xml`, and calls `OpenTelemetryAppender.install(openTelemetry)` from
  [`OpenTelemetryAppenderInstaller`](src/main/java/com/example/otel/OpenTelemetryAppenderInstaller.java).
  The appender version (`2.28.1-alpha`) is chosen to match the OpenTelemetry SDK version that Boot 4.1.2 manages (1.62.0).
- **Endpoints**: none are hard-coded. `@ServiceConnection` (Testcontainers) or `compose.yaml` (Docker Compose)
  supplies the OTLP endpoints for all three signals.
- **Settings** in [`application.properties`](src/main/resources/application.properties): 100% trace sampling
  (the default is 10%), a 10s metrics push interval (the default is 1m), and an HTTP latency histogram so p95/p99 can be queried.

Each request to `GET /greeting?name=X`:
1. creates a server span `http get /greeting`,
2. increments the `greetings` counter and records `http.server.requests`,
3. logs `Greeting X` with the current `trace_id`/`span_id`,
4. returns `{"message":"Hello, X!","traceId":"<trace id>"}`, so you can look up that exact trace and its logs.

## Prerequisites

- JDK 25
- Docker, or Podman with the Docker-compatible socket enabled (`podman machine start`)

## Run the tests

```bash
./mvnw test
```

[`ObservabilityIntegrationTests`](src/test/java/com/example/otel/ObservabilityIntegrationTests.java) starts
`grafana/otel-lgtm` with Testcontainers, calls `/greeting`, and polls each backend until the data shows up:

| Test | Backend API queried | Assertion |
|------|--------------------|-----------|
| `tracesAreExportedToTempo` | Tempo `GET /api/v2/traces/{traceId}` | trace from the response exists, has `service.name=spring-boot4-otel` and span `http get /greeting` |
| `metricsAreExportedToPrometheus` | Prometheus `GET /api/v1/query?query=greetings_total{job="spring-boot4-otel"}` | counter is ≥ 1 |
| `logsAreExportedToLoki` | Loki `GET /loki/api/v1/query_range?query={service_name="spring-boot4-otel"} \|= "<name>"` | log line is `Greeting <name>` and its `trace_id` equals the request's trace ID |

> Spring Boot disables metrics and tracing export in tests by default. The test class turns them back on with
> `@AutoConfigureMetrics` and `@AutoConfigureTracing`.

The first run downloads the `grafana/otel-lgtm` image (~2.6 GB), so it takes a while.

## Run locally and look at the data in Grafana

You can run it in either of two ways:

```bash
# A) Testcontainers (works with Docker or Podman). The Grafana URL (random port) is logged at startup:
#    "Grafana is available at http://localhost:NNNNN (user: admin, password: admin)"
./mvnw spring-boot:test-run

# B) Docker Compose (needs the `docker` CLI with the compose plugin). Grafana is at http://localhost:3000
./mvnw spring-boot:run
```

Generate some traffic:

```bash
for i in $(seq 1 20); do curl -s "http://localhost:8080/greeting?name=user$i"; echo; done
```

To check each signal by hand in Grafana, follow **[docs/grafana-manual-verification.md](docs/grafana-manual-verification.md)**.

## Sending to another backend

To send to a collector or vendor endpoint other than LGTM, set the endpoints explicitly (and turn off Docker Compose):

```properties
spring.docker.compose.enabled=false
management.opentelemetry.logging.export.otlp.endpoint=https://collector.example.com:4318/v1/logs
management.otlp.metrics.export.url=https://collector.example.com:4318/v1/metrics
management.opentelemetry.tracing.export.otlp.endpoint=https://collector.example.com:4318/v1/traces
```

## References

- [Spring Boot – OpenTelemetry support](https://docs.spring.io/spring-boot/4.1/reference/actuator/observability.html#actuator.observability.opentelemetry)
- [Spring Boot – Testcontainers service connections](https://docs.spring.io/spring-boot/4.1/reference/testing/testcontainers.html)
- [Testcontainers Grafana module](https://java.testcontainers.org/modules/grafana/)
- [grafana/docker-otel-lgtm](https://github.com/grafana/docker-otel-lgtm)
