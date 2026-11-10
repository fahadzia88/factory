package com.toptier.custom.feature.service

import com.toptier.custom.checkout.exception.GitNotConfiguredException
import com.toptier.custom.github.exception.GitHubException
import com.toptier.custom.github.exception.GitHubNotConfiguredException
import com.toptier.custom.github.exception.GitHubNotFoundException
import com.toptier.custom.github.exception.GitHubRateLimitedException
import com.toptier.custom.github.exception.GitHubRefusedException
import com.toptier.custom.github.exception.GitHubTimeoutException
import com.toptier.custom.github.exception.GitHubUnreachableException
import org.springframework.http.HttpStatus

/** An HTTP status, `code` and plain `detail` for a failed pull request call. */
data class Problem(val status: HttpStatus, val code: String, val detail: String)

/** Describes the GitHub failures of a feature's pull request calls, for the handler and for a task's `pullError`. */
object PullProblems {

	private const val UNREACHABLE = "GitHub couldn't be reached. Try again."
	private const val REFUSED =
		"GitHub refused the request. The git token may need \"Pull requests: read and write\" on this repository."

	/** The problem for [ex], a [GitHubException] or [GitNotConfiguredException]. */
	fun describe(ex: Exception): Problem = when (ex) {
		is GitNotConfiguredException -> Problem(HttpStatus.SERVICE_UNAVAILABLE, "git_not_configured", ex.message ?: "The git token isn't configured.")
		is GitHubRefusedException ->
			if (ex.status == HttpStatus.FORBIDDEN.value() || ex.status == HttpStatus.UNAUTHORIZED.value()) {
				Problem(HttpStatus.BAD_GATEWAY, "github_error", REFUSED)
			} else {
				Problem(HttpStatus.BAD_GATEWAY, "github_error", UNREACHABLE)
			}
		is GitHubNotFoundException ->
			Problem(HttpStatus.BAD_GATEWAY, "github_error", "GitHub couldn't find the pull request, or the git token can't see it.")
		is GitHubUnreachableException -> Problem(HttpStatus.BAD_GATEWAY, "github_error", UNREACHABLE)
		is GitHubRateLimitedException ->
			Problem(HttpStatus.BAD_GATEWAY, "github_rate_limited", "GitHub's rate limit was reached. Try again in a few minutes.")
		is GitHubNotConfiguredException ->
			Problem(HttpStatus.SERVICE_UNAVAILABLE, "github_not_configured", "The API has no GitHub token configured.")
		is GitHubTimeoutException -> Problem(HttpStatus.GATEWAY_TIMEOUT, "github_timeout", "GitHub didn't answer in time. Try again.")
		else -> Problem(HttpStatus.BAD_GATEWAY, "github_error", UNREACHABLE)
	}

	/** True for the failures [describe] covers. */
	fun covers(ex: Exception) = ex is GitHubException || ex is GitNotConfiguredException
}
