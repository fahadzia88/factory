package com.toptier.custom.github

import com.toptier.custom.github.config.GitHubConfig
import com.toptier.custom.github.config.GitHubProperties
import com.toptier.custom.github.exception.GitHubNotConfiguredException
import com.toptier.custom.github.exception.GitHubNotFoundException
import com.toptier.custom.github.exception.GitHubRateLimitedException
import com.toptier.custom.github.exception.GitHubRefusedException
import com.toptier.custom.github.exception.GitHubTimeoutException
import com.toptier.custom.github.exception.GitHubUnreachableException
import com.toptier.custom.github.service.GitHubPullRequestClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubPullRequestClientTests {

	private val props = GitHubProperties(token = "test-token", baseUrl = GITHUB)
	private val builder = GitHubConfig.configure(RestClient.builder(), props)
	private val server = MockRestServiceServer.bindTo(builder).build()
	private val client = GitHubPullRequestClient(builder.build(), props)

	@AfterEach
	fun verify() {
		server.verify()
	}

	@Test
	fun `lists open pull requests newest first, one page at a time`() {
		server.expect(requestTo("$GITHUB/repos/acme/factory/pulls?state=open&sort=created&direction=desc&per_page=20&page=2"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
			.andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
			.andRespond(withSuccess(PULLS_JSON, MediaType.APPLICATION_JSON))

		val page = client.listOpen("acme", "factory", 2)

		assertEquals(2, page.items.size)
		val (own, fork) = page.items
		assertEquals(42, own.number)
		assertEquals("Add the projects list", own.title)
		assertEquals("octocat", own.user?.login)
		assertEquals("feat/list", own.head.ref)
		assertEquals("acme/factory", own.head.repo?.fullName)
		assertEquals("main", own.base.ref)
		assertEquals(Instant.parse("2026-11-04T10:15:30Z"), own.createdAt)
		assertNull(own.commits)
		assertTrue(fork.draft)
		assertNull(fork.user)
		assertEquals("someone:fix", fork.head.label)
		assertEquals("someone/factory", fork.head.repo?.fullName)
		assertFalse(page.hasNext)
	}

	@Test
	fun `reads whether there is a next page from the Link header`() {
		val link = """<$GITHUB/repositories/1/pulls?page=3>; rel="next", <$GITHUB/repositories/1/pulls?page=5>; rel="last""""
		server.expect(requestTo("$GITHUB/repos/acme/factory/pulls?state=open&sort=created&direction=desc&per_page=20&page=2"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON).header(HttpHeaders.LINK, link))
		server.expect(requestTo("$GITHUB/repos/acme/factory/pulls?state=open&sort=created&direction=desc&per_page=20&page=5"))
			.andRespond(
				withSuccess("[]", MediaType.APPLICATION_JSON)
					.header(HttpHeaders.LINK, """<$GITHUB/repositories/1/pulls?page=4>; rel="prev", <$GITHUB/repositories/1/pulls?page=1>; rel="first""""),
			)

		assertTrue(client.listOpen("acme", "factory", 2).hasNext)
		assertFalse(client.listOpen("acme", "factory", 5).hasNext)
	}

	@Test
	fun `gets one pull request with its counts`() {
		server.expect(requestTo("$GITHUB/repos/acme/factory/pulls/42")).andRespond(withSuccess(PULL_JSON, MediaType.APPLICATION_JSON))

		val pull = client.get("acme", "factory", 42)

		assertEquals("closed", pull.state)
		assertEquals("## Summary\n- Lists projects", pull.body)
		assertEquals(3, pull.commits)
		assertEquals(120, pull.additions)
		assertEquals(14, pull.deletions)
		assertEquals(7, pull.changedFiles)
		assertEquals(Instant.parse("2026-11-05T09:00:00Z"), pull.mergedAt)
		assertEquals(Instant.parse("2026-11-05T09:00:00Z"), pull.closedAt)
	}

	@Test
	fun `lists a page of changed files`() {
		server.expect(requestTo("$GITHUB/repos/acme/factory/pulls/42/files?per_page=30&page=1"))
			.andRespond(withSuccess(FILES_JSON, MediaType.APPLICATION_JSON))

		val page = client.listFiles("acme", "factory", 42, 1)

		val (renamed, binary) = page.items
		assertEquals("app/routes/projects.tsx", renamed.filename)
		assertEquals("app/routes/list.tsx", renamed.previousFilename)
		assertEquals("renamed", renamed.status)
		assertEquals("@@ -1,2 +1,3 @@\n line\n+added", renamed.patch)
		assertEquals(10, renamed.additions)
		assertEquals(2, renamed.deletions)
		assertEquals("$WEB/acme/factory/blob/abc123/app/routes/projects.tsx", renamed.blobUrl)
		assertNull(binary.patch)
		assertNull(binary.previousFilename)
	}

	@Test
	fun `reports a missing repository or pull request as not found`() {
		server.expect(requestTo("$GITHUB/repos/acme/nope/pulls/1"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND).body(NOT_FOUND_JSON).contentType(MediaType.APPLICATION_JSON))

		assertThrows<GitHubNotFoundException> { client.get("acme", "nope", 1) }
	}

	@Test
	fun `reports a rejected token, a refused request and a server error as refused`() {
		for (status in listOf(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.INTERNAL_SERVER_ERROR)) {
			server.expect(requestTo("$GITHUB/repos/acme/factory/pulls/42"))
				.andRespond(withStatus(status).body("""{"message":"Resource not accessible by personal access token"}""").contentType(MediaType.APPLICATION_JSON))
		}

		for (status in listOf(401, 403, 500)) {
			val ex = assertThrows<GitHubRefusedException> { client.get("acme", "factory", 42) }
			assertEquals(status, ex.status)
		}
	}

	@Test
	fun `reports a rate limit from 429 or an exhausted 403`() {
		server.expect(requestTo("$GITHUB/repos/acme/factory/pulls/42")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
		server.expect(requestTo("$GITHUB/repos/acme/factory/pulls/42"))
			.andRespond(
				withStatus(HttpStatus.FORBIDDEN)
					.header("x-ratelimit-remaining", "0")
					.body("""{"message":"API rate limit exceeded"}""")
					.contentType(MediaType.APPLICATION_JSON),
			)

		assertThrows<GitHubRateLimitedException> { client.get("acme", "factory", 42) }
		assertThrows<GitHubRateLimitedException> { client.get("acme", "factory", 42) }
	}

	@Test
	fun `tells a timeout from another connection failure`() {
		server.expect(requestTo("$GITHUB/repos/acme/factory/pulls/42")).andRespond(withException(SocketTimeoutException("Read timed out")))
		server.expect(requestTo("$GITHUB/repos/acme/factory/pulls/42")).andRespond(withException(ConnectException("Connection refused")))

		assertThrows<GitHubTimeoutException> { client.get("acme", "factory", 42) }
		assertThrows<GitHubUnreachableException> { client.get("acme", "factory", 42) }
	}

	@Test
	fun `makes no request without a token`() {
		val noToken = GitHubProperties(token = "", baseUrl = GITHUB)
		val blankBuilder = GitHubConfig.configure(RestClient.builder(), noToken)
		val blankServer = MockRestServiceServer.bindTo(blankBuilder).build()
		val blankClient = GitHubPullRequestClient(blankBuilder.build(), noToken)

		assertThrows<GitHubNotConfiguredException> { blankClient.listOpen("acme", "factory", 1) }
		assertThrows<GitHubNotConfiguredException> { blankClient.get("acme", "factory", 1) }
		assertThrows<GitHubNotConfiguredException> { blankClient.listFiles("acme", "factory", 1, 1) }
		blankServer.verify()
	}

	companion object {
		private const val GITHUB = "https://api.github.com"
		private const val WEB = "https://github.com"

		// Trimmed GET /repos/{owner}/{repo}/pulls response: one pull from a branch, one draft from a fork by a deleted user
		private val PULLS_JSON = """
			[
			 {"url":"$GITHUB/repos/acme/factory/pulls/42","id":1,"number":42,"state":"open","locked":false,
			  "title":"Add the projects list","draft":false,
			  "user":{"login":"octocat","id":1,"avatar_url":"https://avatars.githubusercontent.com/u/1?v=4","type":"User"},
			  "body":"Lists projects.","labels":[],
			  "created_at":"2026-11-04T10:15:30Z","updated_at":"2026-11-05T08:00:00Z","closed_at":null,"merged_at":null,
			  "html_url":"$WEB/acme/factory/pull/42",
			  "head":{"label":"acme:feat/list","ref":"feat/list","sha":"abc123","repo":{"id":1,"full_name":"acme/factory"}},
			  "base":{"label":"acme:main","ref":"main","sha":"def456","repo":{"id":1,"full_name":"acme/factory"}}},
			 {"url":"$GITHUB/repos/acme/factory/pulls/41","id":2,"number":41,"state":"open","title":"Fix typo","draft":true,
			  "user":null,"body":null,
			  "created_at":"2026-11-03T10:00:00Z","updated_at":"2026-11-03T11:00:00Z","closed_at":null,"merged_at":null,
			  "html_url":"$WEB/acme/factory/pull/41",
			  "head":{"label":"someone:fix","ref":"fix","sha":"aaa111","repo":{"id":9,"full_name":"someone/factory"}},
			  "base":{"label":"acme:main","ref":"main","sha":"def456","repo":{"id":1,"full_name":"acme/factory"}}}
			]
		""".trimIndent()

		// Trimmed GET /repos/{owner}/{repo}/pulls/{number} response for a merged pull request
		private val PULL_JSON = """
			{"url":"$GITHUB/repos/acme/factory/pulls/42","id":1,"number":42,"state":"closed","title":"Add the projects list",
			 "draft":false,"merged":true,"mergeable":null,
			 "user":{"login":"octocat","id":1,"avatar_url":"https://avatars.githubusercontent.com/u/1?v=4"},
			 "body":"## Summary\n- Lists projects",
			 "created_at":"2026-11-04T10:15:30Z","updated_at":"2026-11-05T09:00:00Z",
			 "closed_at":"2026-11-05T09:00:00Z","merged_at":"2026-11-05T09:00:00Z",
			 "html_url":"$WEB/acme/factory/pull/42",
			 "head":{"label":"acme:feat/list","ref":"feat/list","sha":"abc123","repo":{"id":1,"full_name":"acme/factory"}},
			 "base":{"label":"acme:main","ref":"main","sha":"def456","repo":{"id":1,"full_name":"acme/factory"}},
			 "comments":0,"review_comments":1,"commits":3,"additions":120,"deletions":14,"changed_files":7}
		""".trimIndent()

		// Trimmed GET /repos/{owner}/{repo}/pulls/{number}/files response: a renamed file and a binary one
		private val FILES_JSON = """
			[
			 {"sha":"bbb","filename":"app/routes/projects.tsx","status":"renamed","additions":10,"deletions":2,"changes":12,
			  "blob_url":"$WEB/acme/factory/blob/abc123/app/routes/projects.tsx",
			  "raw_url":"$WEB/acme/factory/raw/abc123/app/routes/projects.tsx",
			  "patch":"@@ -1,2 +1,3 @@\n line\n+added","previous_filename":"app/routes/list.tsx"},
			 {"sha":"ccc","filename":"app/public/logo.png","status":"added","additions":0,"deletions":0,"changes":0,
			  "blob_url":"$WEB/acme/factory/blob/abc123/app/public/logo.png",
			  "raw_url":"$WEB/acme/factory/raw/abc123/app/public/logo.png"}
			]
		""".trimIndent()

		private const val NOT_FOUND_JSON =
			"""{"message":"Not Found","documentation_url":"https://docs.github.com/rest/pulls/pulls#get-a-pull-request","status":"404"}"""
	}
}
