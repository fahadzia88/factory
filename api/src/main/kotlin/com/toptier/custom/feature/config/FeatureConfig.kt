package com.toptier.custom.feature.config

import com.toptier.custom.agent.config.AgentProperties
import com.toptier.custom.security.config.SecurityProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class FeatureConfig {

	/**
	 * Calls the agent's chat API (agent/README.md, "HTTP API") for feature chats. Same base URL and token as
	 * `agentRestClient`, but the read timeout is the chat idle timeout: the agent sends nothing while a tool runs.
	 */
	@Bean
	fun featureAgentRestClient(
		builder: RestClient.Builder,
		agentProperties: AgentProperties,
		securityProperties: SecurityProperties,
		featureProperties: FeatureProperties,
	): RestClient =
		builder
			.baseUrl(agentProperties.url)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${securityProperties.apiToken}")
			.requestFactory(
				SimpleClientHttpRequestFactory().apply {
					setConnectTimeout(CONNECT_TIMEOUT)
					setReadTimeout(featureProperties.chatIdleTimeout)
				},
			)
			.build()

	companion object {
		private val CONNECT_TIMEOUT = Duration.ofSeconds(5)
	}
}
