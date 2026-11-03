package com.toptier.custom.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Time source for expiries and timestamps, so tests can control "now". */
@Configuration
class ClockConfig {

	@Bean
	fun clock(): Clock = Clock.systemUTC()
}
