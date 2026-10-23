package com.example.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Connects the {@code OTEL} Logback appender declared in {@code logback-spring.xml} to
 * the {@link OpenTelemetry} instance auto-configured by Spring Boot, so that log records
 * are exported over OTLP.
 */
@Component
class OpenTelemetryAppenderInstaller implements InitializingBean {

	private final OpenTelemetry openTelemetry;

	OpenTelemetryAppenderInstaller(OpenTelemetry openTelemetry) {
		this.openTelemetry = openTelemetry;
	}

	@Override
	public void afterPropertiesSet() {
		OpenTelemetryAppender.install(this.openTelemetry);
	}

}
