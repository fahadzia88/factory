package com.toptier.custom.github.service

import com.toptier.custom.pullrequest.dto.PullRequestSummaryResponse
import java.time.Instant

/** A pull request's state and checks, for a feature task. [state] is `open`, `closed` or `merged`. */
data class PullStatus(
	val number: Int,
	val title: String,
	val state: String,
	val draft: Boolean,
	val htmlUrl: String,
	val baseRef: String,
	val headSha: String?,
	val mergedAt: Instant?,
	val checks: ChecksStatus,
) {
	companion object {
		fun from(pull: GitHubPull, runs: List<GitHubCheckRun>) = PullStatus(
			number = pull.number,
			title = pull.title,
			state = PullRequestSummaryResponse.state(pull),
			draft = pull.draft,
			htmlUrl = pull.htmlUrl,
			baseRef = pull.base.ref,
			headSha = pull.head.sha,
			mergedAt = pull.mergedAt,
			checks = ChecksStatus.from(runs),
		)
	}
}

/** The checks on a pull request's head: `none`, `pending`, `success` or `failure`. */
data class ChecksStatus(val state: String, val runs: List<CheckRunStatus>) {
	companion object {
		private val PASSING = setOf("success", "neutral", "skipped")

		fun from(runs: List<GitHubCheckRun>): ChecksStatus {
			val state = when {
				runs.isEmpty() -> "none"
				runs.any { it.status != "completed" } -> "pending"
				runs.any { it.conclusion !in PASSING } -> "failure"
				else -> "success"
			}
			return ChecksStatus(state, runs.map(CheckRunStatus::from))
		}
	}
}

/** One check run, with its result text cut to fit. */
data class CheckRunStatus(
	val name: String,
	val status: String,
	val conclusion: String?,
	val htmlUrl: String?,
	val title: String?,
	val summary: String?,
) {
	companion object {
		fun from(run: GitHubCheckRun) = CheckRunStatus(
			name = run.name,
			status = run.status,
			conclusion = run.conclusion,
			htmlUrl = run.htmlUrl,
			title = run.output?.title?.take(200),
			summary = run.output?.summary?.take(1000),
		)
	}
}
