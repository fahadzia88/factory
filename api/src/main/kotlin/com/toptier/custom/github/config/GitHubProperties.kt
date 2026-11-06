package com.toptier.custom.github.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.github")
data class GitHubProperties(
	/**
	 * Read-only token for checking repositories exist and reading their pull requests ("Metadata: read" and
	 * "Pull requests: read"); blank skips the check and makes pull request reads fail as not configured.
	 */
	val token: String = "",
	/** GitHub REST API base URL, e.g. https://api.github.com */
	val baseUrl: String = "https://api.github.com",
	/** Connect and read timeout for every GitHub call; a slower answer counts as GitHub being unavailable. */
	val timeout: Duration = Duration.ofSeconds(5),
) {
	init {
		require(baseUrl.isNotBlank()) { "app.github.base-url must be set" }
		require(timeout.isPositive) { "app.github.timeout must be positive" }
	}
}
