package com.toptier.custom.github.service

import com.toptier.custom.checkout.config.GitProperties
import com.toptier.custom.checkout.exception.GitNotConfiguredException
import com.toptier.custom.github.exception.GitHubRefusedException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

/**
 * Opens, retargets and reads a feature's pull requests with the git token (`GIT_TOKEN`), which has Pull requests
 * read and write. Every GitHub failure surfaces as a [com.toptier.custom.github.exception.GitHubException].
 */
@Service
class GitHubPullWriteClient(
	@Qualifier("gitHubWriteRestClient") private val client: RestClient,
	private val gitProperties: GitProperties,
) {

	/** Opens a pull request from [head] into [base]. */
	fun create(owner: String, repo: String, title: String, head: String, base: String, body: String): GitHubPull =
		call("$owner/$repo pulls (create)") {
			client.post()
				.uri("/repos/{owner}/{repo}/pulls", owner, repo)
				.contentType(MediaType.APPLICATION_JSON)
				.body(mapOf("title" to title, "head" to head, "base" to base, "body" to body))
				.retrieve()
				.body(GitHubPull::class.java)
		}

	/** Changes a pull request's base branch. */
	fun setBase(owner: String, repo: String, number: Int, base: String): GitHubPull =
		call("$owner/$repo pull $number (base)") {
			client.patch()
				.uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, number)
				.contentType(MediaType.APPLICATION_JSON)
				.body(mapOf("base" to base))
				.retrieve()
				.body(GitHubPull::class.java)
		}

	/** One pull request in any state. */
	fun get(owner: String, repo: String, number: Int): GitHubPull =
		call("$owner/$repo pull $number") {
			client.get()
				.uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, number)
				.retrieve()
				.body(GitHubPull::class.java)
		}

	/** A pull request with the check runs on its head commit. */
	fun status(owner: String, repo: String, number: Int): PullStatus {
		val pull = get(owner, repo, number)
		val sha = pull.head.sha ?: return PullStatus.from(pull, emptyList())
		val runs = call("$owner/$repo check runs $sha") {
			client.get()
				.uri { it.path("/repos/{owner}/{repo}/commits/{sha}/check-runs")
					.queryParam("per_page", CHECK_RUNS_PER_PAGE)
					.build(owner, repo, sha) }
				.retrieve()
				.body(GitHubCheckRuns::class.java)
		}
		return PullStatus.from(pull, runs.checkRuns)
	}

	private fun <T : Any> call(path: String, request: () -> T?): T {
		if (gitProperties.token.isBlank()) throw GitNotConfiguredException()
		return callGitHub(path, request) ?: throw GitHubRefusedException(HttpStatus.OK.value(), "$path (empty body)")
	}

	companion object {
		const val CHECK_RUNS_PER_PAGE = 100
	}
}
