package com.toptier.custom.ticket

import com.toptier.custom.support.MutableClock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper

/** Beans ticket service tests need that the JPA slice doesn't load: a movable clock and a `JsonMapper`. */
@TestConfiguration
class TicketTestConfig {

	@Bean
	fun clock() = MutableClock(START)

	@Bean
	fun jsonMapper(): JsonMapper = JsonMapper.builder().build()

	companion object {
		val START = MutableClock().now
	}
}
