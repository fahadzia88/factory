package com.toptier.custom.github

import com.toptier.custom.checkout.config.GitProperties
import com.toptier.custom.checkout.exception.GitNotConfiguredException
import com.toptier.custom.github.config.GitHubConfig
import com.toptier.custom.github.config.GitHubProperties
import com.toptier.custom.github.exception.GitHubNotFoundException
import com.toptier.custom.github.exception.GitHubRateLimitedException
import com.toptier.custom.github.exception.GitHubRefusedException
import com.toptier.custom.github.exception.GitHubTimeoutException
import com.toptier.custom.github.exception.GitHubUnreachableException
import com.toptier.custom.github.service.GitHubPullWriteClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
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
import kotlin.test.assertNull

class GitHubPullWriteClientTests {

	private val gitHub = GitHubProperties(token = "read-token", baseUrl = GITHUB)
	private val git = GitProperties(token = "git-token")
	private val builder = GitHubConfig.configure(RestClient.builder(), gitHub, git.token)
	private val server = MockRestServiceServer.bindTo(builder).build()
	private val client = GitHubPullWriteClient(builder.build(), git)

	@AfterEach
	fun verify() = server.verify()

	@Test
	fun `create posts the pull request with the git token`() {
		server.expect(requestTo("$GITHUB/repos/acme/factory/pulls"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer git-token"))
			.andExpect(content().json("""{"title":"006 03 api: Task records","head":"feat/006-x/03-api-y","base":"feat/006-x/02-api-z","body":"b"}"""))
			.andRespond(withStatus(HttpStatus.CREATED).body(pull()).contentType(MediaType.APPLICATION_JSON))

		val created = client.create("acme", "factory", "006 03 api: Task records", "feat/006-x/03-api-y", "feat/006-x/02-api-z", "b")

		assertEquals(61, created.number)
		assertEquals("abc1234", created.head.sha)
	}

	@Test
	fun `setBase patches the base`() {
		server.expect(requestTo("$GITHUB/repos/acme/factory/pulls/61"))
			.andExpect(method(HttpMethod.PATCH))
			.andExpect(content().json("""{"base":"main"}"""))
			.andRespond(withSuccess(pull(base = "main"), MediaType.APPLICATION_JSON))

		assertEquals("main", client.setBase("acme", "factory", 61, "main").base.ref)
	}

	@Test
	fun `status of a merged pull request with passing checks`() {
		expectPull(pull(mergedAt = "2026-11-10T10:00:00Z", state = "closed"))
		expectRuns(run("build", "completed", "success"), run("lint", "completed", "skipped"))

		val status = client.status("acme", "factory", 61)

		assertEquals("merged", status.state)
		assertEquals(Instant.parse("2026-11-10T10:00:00Z"), status.mergedAt)
		assertEquals("success", status.checks.state)
		assertEquals("abc1234", status.headSha)
	}

	@Test
	fun `a failing run makes the checks fail and keeps its details`() {
		expectPull(pull())
		expectRuns(run("build", "completed", "success"), run("test", "completed", "failure", title = "2 tests failed", summary = "x".repeat(1500)))

		val status = client.status("acme", "factory", 61)

		assertEquals("open", status.state)
		assertEquals("failure", status.checks.state)
		val failed = status.checks.runs[1]
		assertEquals("test", failed.name)
		assertEquals("failure", failed.conclusion)
		assertEquals("2 tests failed", failed.title)
		assertEquals(1000, failed.summary!!.length)
		assertEquals("https://github.com/acme/factory/runs/2", failed.htmlUrl)
	}

	@Test
	fun `queued runs are pending and no runs are none`() {
		expectPull(pull())
		expectRuns(run("build", "queued", null))
		expectPull(pull())
		expectRuns()

		assertEquals("pending", client.status("acme", "factory", 61).checks.state)
		assertEquals("none", client.status("acme", "factory", 61).checks.state)
	}

	@Test
	fun `a pull request without a head sha has no checks`() {
		expectPull(pull(sha = null))

		val status = client.status("acme", "factory", 61)

		assertNull(status.headSha)
		assertEquals("none", status.checks.state)
	}

	@Test
	fun `GitHub failures map to typed exceptions`() {
		fun expectFailure() = server.expect(requestTo("$GITHUB/repos/acme/factory/pulls/61"))
		expectFailure().andRespond(withStatus(HttpStatus.FORBIDDEN))
		expectFailure().andRespond(withStatus(HttpStatus.NOT_FOUND))
		expectFailure().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
		expectFailure().andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))
		expectFailure().andRespond(withException(SocketTimeoutException("Read timed out")))
		expectFailure().andRespond(withException(ConnectException("Connection refused")))

		assertEquals(403, assertThrows<GitHubRefusedException> { client.get("acme", "factory", 61) }.status)
		assertThrows<GitHubNotFoundException> { client.get("acme", "factory", 61) }
		assertThrows<GitHubRateLimitedException> { client.get("acme", "factory", 61) }
		assertEquals(500, assertThrows<GitHubRefusedException> { client.get("acme", "factory", 61) }.status)
		assertThrows<GitHubTimeoutException> { client.get("acme", "factory", 61) }
		assertThrows<GitHubUnreachableException> { client.get("acme", "factory", 61) }
	}

	@Test
	fun `a blank git token makes no request`() {
		val blank = GitHubPullWriteClient(builder.build(), GitProperties(token = ""))

		assertThrows<GitNotConfiguredException> { blank.create("acme", "factory", "t", "h", "b", "x") }
		assertThrows<GitNotConfiguredException> { blank.status("acme", "factory", 61) }
	}

	private fun expectPull(json: String) {
		server.expect(requestTo("$GITHUB/repos/acme/factory/pulls/61")).andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(json, MediaType.APPLICATION_JSON))
	}

	private fun expectRuns(vararg runs: String) {
		server.expect(requestTo("$GITHUB/repos/acme/factory/commits/abc1234/check-runs?per_page=100"))
			.andRespond(withSuccess("""{"total_count":${runs.size},"check_runs":[${runs.joinToString(",")}]}""", MediaType.APPLICATION_JSON))
	}

	private var runId = 0

	private fun run(name: String, status: String, conclusion: String?, title: String? = null, summary: String? = null): String {
		runId++
		val output = """{"title":${title?.let { "\"$it\"" }},"summary":${summary?.let { "\"$it\"" }},"text":null}"""
		return """{"id":$runId,"name":"$name","status":"$status","conclusion":${conclusion?.let { "\"$it\"" }},"html_url":"https://github.com/acme/factory/runs/$runId","output":$output}"""
	}

	private fun pull(
		base: String = "feat/006-x/02-api-z",
		state: String = "open",
		mergedAt: String? = null,
		sha: String? = "abc1234",
	) = """
		{
		  "number": 61, "title": "006 03 api: Task records", "state": "$state", "draft": false,
		  "user": {"login": "factory", "avatar_url": "https://avatars.githubusercontent.com/u/1"},
		  "head": {"ref": "feat/006-x/03-api-y", "label": "acme:feat/006-x/03-api-y", "repo": {"full_name": "acme/factory"}${sha?.let { ", \"sha\": \"$it\"" } ?: ""}},
		  "base": {"ref": "$base", "label": "acme:$base", "repo": {"full_name": "acme/factory"}, "sha": "def5678"},
		  "body": "b",
		  "created_at": "2026-11-10T09:00:00Z", "updated_at": "2026-11-10T09:30:00Z",
		  "merged_at": ${mergedAt?.let { "\"$it\"" }}, "closed_at": ${mergedAt?.let { "\"$it\"" }},
		  "html_url": "https://github.com/acme/factory/pull/61"
		}
	""".trimIndent()

	private companion object {
		const val GITHUB = "https://api.github.test"
	}
}
