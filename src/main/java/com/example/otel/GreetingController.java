package com.example.otel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GreetingController {

	private static final Logger logger = LoggerFactory.getLogger(GreetingController.class);

	private final Counter greetings;

	private final Tracer tracer;

	GreetingController(MeterRegistry meterRegistry, Tracer tracer) {
		this.greetings = Counter.builder("greetings").description("Number of greetings served").register(meterRegistry);
		this.tracer = tracer;
	}

	@GetMapping("/greeting")
	Greeting greeting(@RequestParam(defaultValue = "World") String name) {
		this.greetings.increment();
		logger.info("Greeting {}", name);
		Span span = this.tracer.currentSpan();
		return new Greeting("Hello, " + name + "!", (span != null) ? span.context().traceId() : null);
	}

	record Greeting(String message, String traceId) {
	}

}
