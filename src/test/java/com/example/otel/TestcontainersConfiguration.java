package com.example.otel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(TestcontainersConfiguration.class);

	/**
	 * Grafana LGTM stack (OpenTelemetry Collector, Loki, Prometheus, Tempo and Grafana).
	 * {@link ServiceConnection @ServiceConnection} points the OTLP exporters for logs,
	 * metrics and traces at the container's collector.
	 */
	@Bean
	@ServiceConnection
	LgtmStackContainer grafanaLgtmContainer() {
		return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:0.38.0"));
	}

	@Bean
	ApplicationListener<ApplicationReadyEvent> grafanaUrlLogger(LgtmStackContainer lgtm) {
		return (event) -> logger.info("Grafana is available at {} (user: admin, password: admin)",
				lgtm.getGrafanaHttpUrl());
	}

}
