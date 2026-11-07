package com.toptier.custom.feature

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper

/** Beans feature service tests need that slice tests don't load: the `JsonMapper` the workflow codec uses. */
@TestConfiguration
class FeatureTestConfig {

	@Bean
	fun jsonMapper(): JsonMapper = JsonMapper.builder().build()
}
