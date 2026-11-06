package com.toptier.custom.checkout.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

/** Settings for the project checkouts the API clones and manages with CLI git. */
@ConfigurationProperties(prefix = "app.git")
data class GitProperties(
	/** Contents read+write token. Sent as `http.https://github.com/.extraheader` through `GIT_CONFIG_*` env per command; never on disk. */
	val token: String = "",
	/** The shared volume with the checkouts; the agent mounts it at the same path. */
	val root: String = "/repos",
	/** Longest wait for a fetch, push or any other git command. */
	val networkTimeout: Duration = Duration.ofMinutes(2),
	/** Longest wait for a clone. */
	val cloneTimeout: Duration = Duration.ofMinutes(10),
	/** How often ready checkouts are fetched (only when `app.scheduling.enabled`). */
	val fetchInterval: Duration = Duration.ofMinutes(15),
) {
	init {
		require(root.isNotBlank()) { "app.git.root must be set" }
		require(Path.of(root).isAbsolute) { "app.git.root must be an absolute path" }
		require(networkTimeout.isPositive) { "app.git.network-timeout must be positive" }
		require(cloneTimeout.isPositive) { "app.git.clone-timeout must be positive" }
		require(fetchInterval.isPositive) { "app.git.fetch-interval must be positive" }
	}
}
