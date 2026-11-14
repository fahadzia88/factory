package com.toptier.custom.homelab.config

import com.toptier.custom.homelab.exception.HomelabNotConfiguredException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.util.concurrent.ConcurrentHashMap

@Configuration
class HomelabConfig {

	/** The only place that builds controller clients, and so the only reader of the bearer token. */
	@Bean
	fun homelabClients(builder: RestClient.Builder, properties: HomelabProperties): HomelabClients =
		HomelabClients(builder, properties)
}

/**
 * Builds and caches one `RestClient` per controller address, with the bearer token and the timeouts. [applyTimeouts]
 * is false only in tests that bind a mock server to the [builder] and must keep its request factory.
 */
class HomelabClients(
	private val builder: RestClient.Builder,
	private val properties: HomelabProperties,
	private val applyTimeouts: Boolean = true,
) {

	private val json = ConcurrentHashMap<String, RestClient>()
	private val streams = ConcurrentHashMap<String, RestClient>()

	/** A client for normal calls: JSON accepted, connect and read timeout of `app.homelab.timeout`. */
	fun json(address: String): RestClient {
		requireToken()
		return json.computeIfAbsent(address) { build(it, stream = false) }
	}

	/** A client for streams (watch, follow logs): the connect timeout only, so a quiet stream isn't cut. */
	fun stream(address: String): RestClient {
		requireToken()
		return streams.computeIfAbsent(address) { build(it, stream = true) }
	}

	private fun requireToken() {
		if (properties.token.isBlank()) throw HomelabNotConfiguredException()
	}

	private fun build(address: String, stream: Boolean): RestClient {
		val clone = builder.clone()
			.baseUrl(address)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.token}")
		if (!stream) clone.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
		if (applyTimeouts) {
			clone.requestFactory(
				SimpleClientHttpRequestFactory().apply {
					setConnectTimeout(properties.timeout)
					if (!stream) setReadTimeout(properties.timeout)
				},
			)
		}
		return clone.build()
	}
}
