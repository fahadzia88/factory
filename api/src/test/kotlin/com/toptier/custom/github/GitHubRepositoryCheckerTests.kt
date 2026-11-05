package com.toptier.custom.github

import com.toptier.custom.github.config.GitHubConfig
import com.toptier.custom.github.config.GitHubProperties
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.github.service.RepositoryCheck
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException
import kotlin.test.assertEquals

class GitHubRepositoryCheckerTests {

	private val props = GitHubProperties(token = "test-token", baseUrl = GITHUB)
	private val builder = GitHubConfig.configure(RestClient.builder(), props)
	private val server = MockRestServiceServer.bindTo(builder).build()
	private val checker = GitHubRepositoryChecker(builder.build(), props)

	@AfterEach
	fun verify() {
		server.verify()
	}

	@Test
	fun `finds a repository the token can see`() {
		server.expect(requestTo("$GITHUB/repos/acme/factory"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
			.andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
			.andRespond(withSuccess(REPO_JSON, MediaType.APPLICATION_JSON))

		assertEquals(RepositoryCheck.FOUND, checker.check("acme", "factory"))
	}

	@Test
	fun `reports a missing or invisible repository as not found`() {
		server.expect(requestTo("$GITHUB/repos/acme/nope"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND).body(NOT_FOUND_JSON).contentType(MediaType.APPLICATION_JSON))

		assertEquals(RepositoryCheck.NOT_FOUND, checker.check("acme", "nope"))
	}

	@Test
	fun `treats a rate limit as unavailable`() {
		server.expect(requestTo("$GITHUB/repos/acme/factory"))
			.andRespond(
				withStatus(HttpStatus.FORBIDDEN)
					.header("x-ratelimit-remaining", "0")
					.body("""{"message":"API rate limit exceeded"}""")
					.contentType(MediaType.APPLICATION_JSON),
			)

		assertEquals(RepositoryCheck.UNAVAILABLE, checker.check("acme", "factory"))
	}

	@Test
	fun `treats too many requests as unavailable`() {
		server.expect(requestTo("$GITHUB/repos/acme/factory")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

		assertEquals(RepositoryCheck.UNAVAILABLE, checker.check("acme", "factory"))
	}

	@Test
	fun `treats a rejected token as unavailable`() {
		server.expect(requestTo("$GITHUB/repos/acme/factory")).andRespond(withStatus(HttpStatus.UNAUTHORIZED))

		assertEquals(RepositoryCheck.UNAVAILABLE, checker.check("acme", "factory"))
	}

	@Test
	fun `treats a server error as unavailable`() {
		server.expect(requestTo("$GITHUB/repos/acme/factory")).andRespond(withStatus(HttpStatus.BAD_GATEWAY))

		assertEquals(RepositoryCheck.UNAVAILABLE, checker.check("acme", "factory"))
	}

	@Test
	fun `treats a timeout as unavailable`() {
		server.expect(requestTo("$GITHUB/repos/acme/factory")).andRespond(withException(SocketTimeoutException("Read timed out")))

		assertEquals(RepositoryCheck.UNAVAILABLE, checker.check("acme", "factory"))
	}

	@Test
	fun `makes no request without a token`() {
		val noToken = GitHubProperties(token = "", baseUrl = GITHUB)
		val blankBuilder = GitHubConfig.configure(RestClient.builder(), noToken)
		val blankServer = MockRestServiceServer.bindTo(blankBuilder).build()

		assertEquals(RepositoryCheck.UNAVAILABLE, GitHubRepositoryChecker(blankBuilder.build(), noToken).check("acme", "factory"))
		blankServer.verify()
	}

	@Test
	fun `encodes the owner and repository`() {
		server.expect(requestTo("$GITHUB/repos/acme/my.repo")).andRespond(withSuccess(REPO_JSON, MediaType.APPLICATION_JSON))
		server.expect(requestTo("$GITHUB/repos/acme/my%20repo")).andRespond(withStatus(HttpStatus.NOT_FOUND))

		assertEquals(RepositoryCheck.FOUND, checker.check("acme", "my.repo"))
		assertEquals(RepositoryCheck.NOT_FOUND, checker.check("acme", "my repo"))
	}

	companion object {
		private const val GITHUB = "https://api.github.com"

		// Trimmed GET /repos/{owner}/{repo} response
		private val REPO_JSON = """
			{"id":1296269,"name":"factory","full_name":"acme/factory","private":true,
			 "owner":{"login":"acme","id":1},"html_url":"https://github.com/acme/factory","default_branch":"main"}
		""".trimIndent()

		private const val NOT_FOUND_JSON =
			"""{"message":"Not Found","documentation_url":"https://docs.github.com/rest/repos/repos#get-a-repository","status":"404"}"""
	}
}
