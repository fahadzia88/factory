package com.toptier.custom.github.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration
class GitHubConfig {

	/** Calls the GitHub REST API, with the configured token when there is one. */
	@Bean
	fun gitHubRestClient(builder: RestClient.Builder, gitHubProperties: GitHubProperties): RestClient =
		configure(builder, gitHubProperties).build()

	companion object {
		/** Applies the GitHub base URL, headers and timeouts; shared with tests so they bind the same setup. */
		fun configure(builder: RestClient.Builder, props: GitHubProperties): RestClient.Builder {
			builder
				.baseUrl(props.baseUrl)
				.defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
				.defaultHeader("X-GitHub-Api-Version", "2022-11-28")
				.requestFactory(
					SimpleClientHttpRequestFactory().apply {
						setConnectTimeout(props.timeout)
						setReadTimeout(props.timeout)
					},
				)
			if (props.token.isNotBlank()) builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${props.token}")
			return builder
		}
	}
}
