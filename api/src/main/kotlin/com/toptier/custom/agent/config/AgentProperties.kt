package com.toptier.custom.agent.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.agent")
data class AgentProperties(
	/** Base URL of the eve agent, e.g. http://agent:3000 */
	val url: String,
	/** How long one [com.toptier.custom.agent.service.AgentService.ask] may take, from send to settled reply. */
	val timeout: Duration = Duration.ofMinutes(2),
) {
	init {
		require(url.isNotBlank()) { "app.agent.url must be set" }
		require(timeout.isPositive) { "app.agent.timeout must be positive" }
	}
}
