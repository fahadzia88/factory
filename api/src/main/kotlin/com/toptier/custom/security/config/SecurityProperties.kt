package com.toptier.custom.security.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.security")
data class SecurityProperties(
	val apiToken: String,
) {
	init {
		require(apiToken.isNotBlank()) { "app.security.api-token must be set" }
	}
}
