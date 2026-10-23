package com.example.otel;

import org.springframework.boot.SpringApplication;

public class TestSpringBoot4OtelApplication {

	public static void main(String[] args) {
		// Testcontainers provides the Grafana LGTM stack, so Docker Compose is not needed
		System.setProperty("spring.docker.compose.enabled", "false");
		SpringApplication.from(SpringBoot4OtelApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
