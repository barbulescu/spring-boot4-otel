package com.example.otel;

import org.springframework.boot.SpringApplication;

public class TestSpringBoot4OtelApplication {

	public static void main(String[] args) {
		SpringApplication.from(SpringBoot4OtelApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
