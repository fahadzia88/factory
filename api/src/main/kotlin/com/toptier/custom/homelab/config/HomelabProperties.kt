package com.toptier.custom.homelab.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.homelab")
data class HomelabProperties(
	/**
	 * Bearer token of the HomeLab Controller (`CONTROLLER_API_TOKEN`). It effectively grants cluster-admin access, so it
	 * is read only here, sent only as a header, and never returned or logged. Blank: calls fail as not configured.
	 */
	val token: String = "",
	/** Connect and read timeout of every controller call except streams; a slower answer counts as unreachable. */
	val timeout: Duration = Duration.ofSeconds(10),
	/** How long a cluster's `GET /api` answer (read-only mode, namespace selector) is reused. */
	val statusCache: Duration = Duration.ofSeconds(30),
	/** First wait before reconnecting a watch stream the controller closed; doubles up to [watchBackoffMax]. */
	val watchBackoffInitial: Duration = Duration.ofSeconds(1),
	/** Longest wait between reconnects of a watch stream. */
	val watchBackoffMax: Duration = Duration.ofSeconds(30),
) {
	init {
		require(timeout.isPositive) { "app.homelab.timeout must be positive" }
		require(statusCache.isPositive) { "app.homelab.status-cache must be positive" }
		require(watchBackoffInitial.isPositive) { "app.homelab.watch-backoff-initial must be positive" }
		require(watchBackoffMax >= watchBackoffInitial) {
			"app.homelab.watch-backoff-max must not be shorter than app.homelab.watch-backoff-initial"
		}
	}

	/** Never prints the token. */
	override fun toString(): String =
		"HomelabProperties(token=${if (token.isBlank()) "<not set>" else "<hidden>"}, timeout=$timeout, " +
			"statusCache=$statusCache, watchBackoffInitial=$watchBackoffInitial, watchBackoffMax=$watchBackoffMax)"
}
