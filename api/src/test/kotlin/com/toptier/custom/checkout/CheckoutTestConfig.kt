package com.toptier.custom.checkout

import com.toptier.custom.checkout.config.GitProperties
import com.toptier.custom.support.MutableClock
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.test.context.DynamicPropertyRegistrar
import java.nio.file.Files
import java.nio.file.Path

/** Shared beans for checkout service tests: a movable clock, git settings on a temp root, and clones that finish inside `start`. */
@TestConfiguration
@EnableConfigurationProperties(GitProperties::class)
class CheckoutTestConfig {

	@Bean
	fun clock() = MutableClock(START)

	/** `GitProperties(token = "test-token", root = ROOT)`, set as properties because a `@Bean` of a properties class is rebound from the environment. */
	@Bean
	fun gitPropertiesRegistrar() = DynamicPropertyRegistrar {
		it.add("app.git.token") { "test-token" }
		it.add("app.git.root") { ROOT.toString() }
	}

	@Bean("checkoutExecutor")
	fun checkoutExecutor() = SyncTaskExecutor()

	companion object {
		/** The checkouts' root folder; tests empty it after each run. */
		val ROOT: Path = Files.createTempDirectory("checkouts")

		val START = MutableClock().now
	}
}
