package com.toptier.custom.agent.config

import com.toptier.custom.security.config.SecurityProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class AgentConfig {

	/** Calls the eve agent with the shared API token, which the agent checks like the API does. */
	@Bean
	fun agentRestClient(
		builder: RestClient.Builder,
		agentProperties: AgentProperties,
		securityProperties: SecurityProperties,
	): RestClient =
		builder
			.baseUrl(agentProperties.url)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${securityProperties.apiToken}")
			// HttpURLConnection's read timeout applies to each read, so it bounds a stalled stream; the agent sends
			// heartbeats while a stream is quiet
			.requestFactory(
				SimpleClientHttpRequestFactory().apply {
					setConnectTimeout(CONNECT_TIMEOUT)
					setReadTimeout(agentProperties.timeout.coerceAtMost(STREAM_IDLE_TIMEOUT))
				},
			)
			.build()

	companion object {
		private val CONNECT_TIMEOUT = Duration.ofSeconds(5)
		private val STREAM_IDLE_TIMEOUT = Duration.ofSeconds(30)
	}
}
