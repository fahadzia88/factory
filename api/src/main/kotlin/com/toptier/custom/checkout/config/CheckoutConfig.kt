package com.toptier.custom.checkout.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/** Beans for the checkout feature. */
@Configuration
class CheckoutConfig {

	/**
	 * Runs clones in the background. Shutdown doesn't wait for them: a clone cut off by a restart
	 * stays `CLONING`, and the startup reset marks it failed.
	 */
	@Bean("checkoutExecutor")
	fun checkoutExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 2
		maxPoolSize = 2
		queueCapacity = 20
		setThreadNamePrefix("checkout-clone-")
		setWaitForTasksToCompleteOnShutdown(false)
	}
}
