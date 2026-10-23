package com.example.otel;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SpringBoot4OtelApplicationTests {

	@Test
	void contextLoads() {
	}

}
