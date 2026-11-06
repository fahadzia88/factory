package com.toptier.custom.pullrequest.service

import com.toptier.custom.github.exception.GitHubNotFoundException
import com.toptier.custom.github.service.GitHubPullRequestClient
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.github.service.RepositoryCheck
import com.toptier.custom.project.service.GitHubRepositoryUrl
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.pullrequest.dto.PageResponse
import com.toptier.custom.pullrequest.dto.PullRequestFileResponse
import com.toptier.custom.pullrequest.dto.PullRequestResponse
import com.toptier.custom.pullrequest.dto.PullRequestSummaryResponse
import com.toptier.custom.pullrequest.exception.InvalidPullRequestParameterException
import com.toptier.custom.pullrequest.exception.PullRequestNotFoundException
import com.toptier.custom.pullrequest.exception.PullRequestRepositoryNotFoundException
import org.springframework.stereotype.Service

/**
 * Reads a project's pull requests live from GitHub. Not transactional: only the project lookup touches the
 * database, so no transaction is held open across a GitHub call.
 */
@Service
class PullRequestService(
	private val projectService: ProjectService,
	private val client: GitHubPullRequestClient,
	private val checker: GitHubRepositoryChecker,
) {

	/** One page of the open pull requests, newest created first; archived and unverified projects included. */
	fun list(projectId: Long, page: Int?): PageResponse<PullRequestSummaryResponse> {
		val pageNumber = positive(page ?: 1, "page")
		val repository = repositoryOf(projectId)
		val pulls = try {
			client.listOpen(repository.owner, repository.repo, pageNumber)
		} catch (_: GitHubNotFoundException) {
			throw PullRequestRepositoryNotFoundException(projectId)
		}
		return PageResponse.from(pulls, pageNumber, GitHubPullRequestClient.PULLS_PER_PAGE, PullRequestSummaryResponse::from)
	}

	/** One pull request, in any state. */
	fun get(projectId: Long, number: Int): PullRequestResponse {
		positive(number, "number")
		val repository = repositoryOf(projectId)
		val pull = notFoundAsMissing(projectId, number, repository) { client.get(repository.owner, repository.repo, number) }
		return PullRequestResponse.from(pull)
	}

	/** One page of the files a pull request changes. */
	fun files(projectId: Long, number: Int, page: Int?): PageResponse<PullRequestFileResponse> {
		positive(number, "number")
		val pageNumber = positive(page ?: 1, "page")
		val repository = repositoryOf(projectId)
		val files = notFoundAsMissing(projectId, number, repository) {
			client.listFiles(repository.owner, repository.repo, number, pageNumber)
		}
		return PageResponse.from(files, pageNumber, GitHubPullRequestClient.FILES_PER_PAGE, PullRequestFileResponse::from)
	}

	private fun repositoryOf(projectId: Long): GitHubRepositoryUrl {
		val project = projectService.get(projectId)
		return checkNotNull(GitHubRepositoryUrl.parse(project.repositoryUrl)) { "Stored URL of project $projectId is invalid" }
	}

	/** GitHub answers 404 for a missing repository and a missing pull request alike; asking about the repository tells them apart. */
	private fun <T> notFoundAsMissing(projectId: Long, number: Int, repository: GitHubRepositoryUrl, call: () -> T): T =
		try {
			call()
		} catch (_: GitHubNotFoundException) {
			when (checker.check(repository.owner, repository.repo)) {
				RepositoryCheck.NOT_FOUND -> throw PullRequestRepositoryNotFoundException(projectId)
				else -> throw PullRequestNotFoundException(projectId, number)
			}
		}

	private fun positive(value: Int, name: String): Int {
		if (value < 1) throw InvalidPullRequestParameterException(name)
		return value
	}
}
