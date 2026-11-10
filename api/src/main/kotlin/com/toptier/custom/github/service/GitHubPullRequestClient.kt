package com.toptier.custom.github.service

import com.toptier.custom.github.config.GitHubProperties
import com.toptier.custom.github.exception.GitHubNotConfiguredException
import com.toptier.custom.github.exception.GitHubRefusedException
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

/** Reads pull requests from GitHub; every failure surfaces as a [com.toptier.custom.github.exception.GitHubException]. */
@Service
class GitHubPullRequestClient(
	private val gitHubRestClient: RestClient,
	private val gitHubProperties: GitHubProperties,
) {

	/** One page of the repository's open pull requests, newest created first. */
	fun listOpen(owner: String, repo: String, page: Int): GitHubPage<GitHubPull> {
		val path = "$owner/$repo pulls"
		val response = call(path) {
			gitHubRestClient.get()
				.uri { it.path("/repos/{owner}/{repo}/pulls")
					.queryParam("state", "open")
					.queryParam("sort", "created")
					.queryParam("direction", "desc")
					.queryParam("per_page", PULLS_PER_PAGE)
					.queryParam("page", page)
					.build(owner, repo) }
				.retrieve()
				.toEntity(PULL_LIST)
		}
		return page(response)
	}

	/** One pull request in any state, with its commit, line and file counts. */
	fun get(owner: String, repo: String, number: Int): GitHubPull =
		call("$owner/$repo pull $number") {
			gitHubRestClient.get()
				.uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, number)
				.retrieve()
				.body(GitHubPull::class.java)
		} ?: throw GitHubRefusedException(HttpStatus.OK.value(), "$owner/$repo pull $number (empty body)")

	/** One page of the files a pull request changes, in GitHub's order. */
	fun listFiles(owner: String, repo: String, number: Int, page: Int): GitHubPage<GitHubFile> {
		val response = call("$owner/$repo pull $number files") {
			gitHubRestClient.get()
				.uri { it.path("/repos/{owner}/{repo}/pulls/{number}/files")
					.queryParam("per_page", FILES_PER_PAGE)
					.queryParam("page", page)
					.build(owner, repo, number) }
				.retrieve()
				.toEntity(FILE_LIST)
		}
		return page(response)
	}

	private fun <T> page(response: ResponseEntity<List<T>>): GitHubPage<T> {
		val hasNext = response.headers[HttpHeaders.LINK].orEmpty().any { it.contains("rel=\"next\"") }
		return GitHubPage(response.body.orEmpty(), hasNext)
	}

	/** Runs one GitHub request, turning every way it can fail into a typed exception. Never logs the token. */
	private fun <T> call(path: String, request: () -> T): T {
		if (gitHubProperties.token.isBlank()) throw GitHubNotConfiguredException()
		return callGitHub(path, request)
	}

	companion object {
		const val PULLS_PER_PAGE = 20
		const val FILES_PER_PAGE = 30
		private val PULL_LIST = object : ParameterizedTypeReference<List<GitHubPull>>() {}
		private val FILE_LIST = object : ParameterizedTypeReference<List<GitHubFile>>() {}
	}
}
