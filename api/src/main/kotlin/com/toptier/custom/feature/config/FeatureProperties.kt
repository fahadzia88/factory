package com.toptier.custom.feature.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Settings for the features created inside projects. */
@ConfigurationProperties(prefix = "app.feature")
data class FeatureProperties(
	/** Author and committer name of the commit the API makes when it creates a feature. */
	val commitName: String = "factory",
	/** Author and committer email of that commit. */
	val commitEmail: String = "factory@users.noreply.github.com",
	/** Longest silence on the agent's chat stream (a long tool call) before a feature chat turn fails. */
	val chatIdleTimeout: Duration = Duration.ofMinutes(5),
) {
	init {
		require(commitName.isNotBlank()) { "app.feature.commit-name must be set" }
		require(commitEmail.isNotBlank()) { "app.feature.commit-email must be set" }
		require("@" in commitEmail) { "app.feature.commit-email must be an email address" }
		require(chatIdleTimeout.isPositive) { "app.feature.chat-idle-timeout must be positive" }
	}
}
