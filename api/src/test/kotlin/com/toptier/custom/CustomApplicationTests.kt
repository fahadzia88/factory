package com.toptier.custom

import org.junit.jupiter.api.Test
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
// Embedded database instead of MySQL; no background jobs
@AutoConfigureTestDatabase
@TestPropertySource(properties = ["app.scheduling.enabled=false"])
class CustomApplicationTests {

	@Test
	fun contextLoads() {
	}

}
