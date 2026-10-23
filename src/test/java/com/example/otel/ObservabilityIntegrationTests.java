package com.example.otel;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.grafana.LgtmStackContainer;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.otel.GreetingController.Greeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Calls the application and verifies that the resulting trace, metric and log record
 * arrive in Tempo, Prometheus and Loki running inside the {@code grafana/otel-lgtm}
 * container.
 * <p>
 * Metrics and tracing export are disabled in tests by default; {@link AutoConfigureMetrics}
 * and {@link AutoConfigureTracing} switch them back on.
 */
@AutoConfigureMetrics
@AutoConfigureTracing
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.otlp.metrics.export.step=1s")
class ObservabilityIntegrationTests {

	private static final String SERVICE_NAME = "spring-boot4-otel";

	@LocalServerPort
	private int port;

	@Autowired
	private LgtmStackContainer lgtm;

	private final RestClient restClient = RestClient.create();

	private String name;

	@BeforeEach
	void setUp() {
		this.name = "test-" + UUID.randomUUID();
	}

	@Test
	void tracesAreExportedToTempo() {
		String traceId = greet().traceId();
		assertThat(traceId).isNotBlank();
		JsonNode response = awaitJson(this.lgtm.getTempoUrl() + "/api/v2/traces/" + traceId,
				(json) -> !json.path("trace").path("resourceSpans").isEmpty());
		JsonNode resourceSpans = response.path("trace").path("resourceSpans");
		assertThat(resourceSpans.toString()).contains("\"service.name\"")
			.contains(SERVICE_NAME)
			.contains("http get /greeting");
	}

	@Test
	void metricsAreExportedToPrometheus() {
		greet();
		URI uri = uri(this.lgtm.getPrometheusHttpUrl() + "/api/v1/query", "query",
				"greetings_total{job=\"" + SERVICE_NAME + "\"}");
		JsonNode response = awaitJson(uri, (json) -> !json.path("data").path("result").isEmpty());
		JsonNode result = response.path("data").path("result").get(0);
		assertThat(result.path("metric").path("job").asString()).isEqualTo(SERVICE_NAME);
		assertThat(Double.parseDouble(result.path("value").get(1).asString())).isGreaterThanOrEqualTo(1);
	}

	@Test
	void logsAreExportedToLoki() {
		String traceId = greet().traceId();
		URI uri = uri(this.lgtm.getLokiUrl() + "/loki/api/v1/query_range", "query",
				"{service_name=\"" + SERVICE_NAME + "\"} |= \"" + this.name + "\"");
		JsonNode response = awaitJson(uri, (json) -> !json.path("data").path("result").isEmpty());
		JsonNode stream = response.path("data").path("result").get(0);
		assertThat(stream.path("values").get(0).get(1).asString()).isEqualTo("Greeting " + this.name);
		assertThat(stream.path("stream").path("trace_id").asString()).isEqualTo(traceId);
	}

	private Greeting greet() {
		return this.restClient.get()
			.uri("http://localhost:{port}/greeting?name={name}", this.port, this.name)
			.retrieve()
			.body(Greeting.class);
	}

	private JsonNode awaitJson(String url, Predicate<JsonNode> condition) {
		return awaitJson(URI.create(url), condition);
	}

	private JsonNode awaitJson(URI uri, Predicate<JsonNode> condition) {
		return await().atMost(Duration.ofSeconds(60))
			.pollInterval(Duration.ofSeconds(1))
			.ignoreExceptions()
			.until(() -> this.restClient.get().uri(uri).retrieve().body(JsonNode.class), condition);
	}

	private static URI uri(String url, String param, String value) {
		// Expanded as a variable so that braces in PromQL/LogQL are not treated as a URI template
		return UriComponentsBuilder.fromUriString(url).queryParam(param, "{value}").encode().buildAndExpand(value).toUri();
	}

}
