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
- **Endpoints**: `application.properties` points all three signals at `http://localhost:4318` (the stack from
  `podman compose up`). In tests and `spring-boot:test-run`, `@ServiceConnection` (Testcontainers) overrides them with the
  container's mapped ports.
- **Settings** in [`application.properties`](src/main/resources/application.properties): 100% trace sampling
  (the default is 10%), a 10s metrics push interval (the default is 1m), and an HTTP latency histogram so p95/p99 can be queried.

Each request to `GET /greeting?name=X`:
1. creates a server span `http get /greeting`,
2. increments the `greetings` counter and records `http.server.requests`,
3. logs `Greeting X` with the current `trace_id`/`span_id`,
4. returns `{"message":"Hello, X!","traceId":"<trace id>"}`, so you can look up that exact trace and its logs.

## Prerequisites

- JDK 25
- [Podman](https://podman.io/) 5.x with a running machine (`podman machine init && podman machine start` on macOS/Windows)
- For `podman compose`: a compose provider (`brew install podman-compose`, or `docker-compose` on the `PATH`)

### Pointing Testcontainers at Podman

Testcontainers talks to Podman through its Docker-compatible API. If the `podman machine start` output says
*"Docker API clients default to this address"* (the `podman-mac-helper` links `/var/run/docker.sock` to Podman), you don't need to configure anything.
If it doesn't, export these before running Maven (add them to `~/.zshrc` to make them permanent):

```bash
# macOS / Windows (Podman machine)
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock

# Linux (rootless Podman)
systemctl --user enable --now podman.socket
export DOCKER_HOST="unix://${XDG_RUNTIME_DIR}/podman/podman.sock"
```

[`src/test/resources/testcontainers.properties`](src/test/resources/testcontainers.properties) sets
`ryuk.container.privileged=true`. Rootless Podman needs this for the Ryuk cleanup container to start.

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

The first run downloads the `grafana/otel-lgtm` image (~2.6 GB), so it takes a while. To download it ahead of time:
`podman pull docker.io/grafana/otel-lgtm:0.38.0`.

## Run locally and look at the data in Grafana

You can run it in either of two ways:

```bash
# A) Testcontainers on Podman: one command; the stack stops when the app stops.
#    The Grafana URL (random port) is logged at startup:
#    "Grafana is available at http://localhost:NNNNN (user: admin, password: admin)"
./mvnw spring-boot:test-run

# B) Long-running stack with Podman. Grafana is at http://localhost:3000 and keeps its data across app restarts.
podman compose up -d
#    (or, without a compose provider:
#     podman run -d --name lgtm -p 3000:3000 -p 4317:4317 -p 4318:4318 docker.io/grafana/otel-lgtm:0.38.0)
./mvnw spring-boot:run
podman compose down            # when finished (or: podman rm -f lgtm)
```

Generate some traffic:

```bash
for i in $(seq 1 20); do curl -s "http://localhost:8080/greeting?name=user$i"; echo; done
```

To check each signal by hand in Grafana, follow **[docs/grafana-manual-verification.md](docs/grafana-manual-verification.md)**.

## Sending to another backend

To send to a collector or vendor endpoint other than LGTM, override the endpoints:

```properties
management.opentelemetry.logging.export.otlp.endpoint=https://collector.example.com:4318/v1/logs
management.otlp.metrics.export.url=https://collector.example.com:4318/v1/metrics
management.opentelemetry.tracing.export.otlp.endpoint=https://collector.example.com:4318/v1/traces
```

## References

- [Spring Boot – OpenTelemetry support](https://docs.spring.io/spring-boot/4.1/reference/actuator/observability.html#actuator.observability.opentelemetry)
- [Spring Boot – Testcontainers service connections](https://docs.spring.io/spring-boot/4.1/reference/testing/testcontainers.html)
- [Testcontainers Grafana module](https://java.testcontainers.org/modules/grafana/)
- [grafana/docker-otel-lgtm](https://github.com/grafana/docker-otel-lgtm)
