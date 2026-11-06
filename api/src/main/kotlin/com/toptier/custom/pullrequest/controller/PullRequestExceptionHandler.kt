package com.toptier.custom.pullrequest.controller

import com.toptier.custom.github.exception.GitHubException
import com.toptier.custom.github.exception.GitHubNotConfiguredException
import com.toptier.custom.github.exception.GitHubNotFoundException
import com.toptier.custom.github.exception.GitHubRateLimitedException
import com.toptier.custom.github.exception.GitHubRefusedException
import com.toptier.custom.github.exception.GitHubTimeoutException
import com.toptier.custom.github.exception.GitHubUnreachableException
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.pullrequest.exception.InvalidPullRequestParameterException
import com.toptier.custom.pullrequest.exception.PullRequestException
import com.toptier.custom.pullrequest.exception.PullRequestNotFoundException
import com.toptier.custom.pullrequest.exception.PullRequestRepositoryNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Maps pull request and GitHub failures to ProblemDetail, each with a `code` the app switches on. */
@RestControllerAdvice(basePackageClasses = [PullRequestController::class])
class PullRequestExceptionHandler {

	private val log = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler(ProjectNotFoundException::class)
	fun handleProjectNotFound(ex: ProjectNotFoundException): ProblemDetail =
		problem(HttpStatus.NOT_FOUND, "project_not_found", "Project not found.")

	@ExceptionHandler(PullRequestException::class)
	fun handlePullRequest(ex: PullRequestException): ProblemDetail = when (ex) {
		is PullRequestNotFoundException -> problem(HttpStatus.NOT_FOUND, "pull_request_not_found", "Pull request not found.")
		is PullRequestRepositoryNotFoundException -> repositoryNotFound()
		is InvalidPullRequestParameterException -> problem(HttpStatus.BAD_REQUEST, "invalid_request", ex.message ?: "Invalid request.")
	}

	@ExceptionHandler(GitHubException::class)
	fun handleGitHub(ex: GitHubException): ProblemDetail {
		if (ex !is GitHubNotFoundException) log.info("Pull request read failed: {}", ex.message)
		return when (ex) {
			is GitHubRefusedException ->
				if (ex.status == HttpStatus.FORBIDDEN.value()) problem(HttpStatus.BAD_GATEWAY, GITHUB_ERROR, REFUSED)
				else problem(HttpStatus.BAD_GATEWAY, GITHUB_ERROR, UNREACHABLE)
			is GitHubUnreachableException -> problem(HttpStatus.BAD_GATEWAY, GITHUB_ERROR, UNREACHABLE)
			is GitHubRateLimitedException ->
				problem(HttpStatus.BAD_GATEWAY, "github_rate_limited", "GitHub's rate limit was reached. Try again in a few minutes.")
			is GitHubNotConfiguredException ->
				problem(HttpStatus.SERVICE_UNAVAILABLE, "github_not_configured", "The API has no GitHub token configured.")
			is GitHubTimeoutException -> problem(HttpStatus.GATEWAY_TIMEOUT, "github_timeout", "GitHub didn't answer in time. Try again.")
			is GitHubNotFoundException -> repositoryNotFound()
		}
	}

	/** A path or query value of the wrong type, e.g. `page=abc` or a non-numeric pull request number. */
	@ExceptionHandler(MethodArgumentTypeMismatchException::class)
	fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail =
		problem(HttpStatus.BAD_REQUEST, "invalid_request", "Invalid value for ${ex.name}.")

	private fun repositoryNotFound() = problem(
		HttpStatus.NOT_FOUND,
		"repository_not_found",
		"GitHub couldn't find this repository, or the API's token can't see it.",
	)

	private fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
		ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty("code", code) }

	companion object {
		private const val GITHUB_ERROR = "github_error"
		private const val UNREACHABLE = "GitHub couldn't be reached. Try again."
		private const val REFUSED = "GitHub refused the request. The API's token may need \"Pull requests: read\" on this repository."
	}
}
