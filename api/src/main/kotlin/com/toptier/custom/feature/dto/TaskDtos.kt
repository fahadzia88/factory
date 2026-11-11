package com.toptier.custom.feature.dto

import com.toptier.custom.feature.service.TaskView
import com.toptier.custom.github.service.CheckRunStatus
import com.toptier.custom.github.service.ChecksStatus
import com.toptier.custom.github.service.PullStatus
import java.time.Instant

/** One branch of a feature's stack: row 0 is the docs branch. */
data class TaskResponse(
	val number: Int,
	val layer: String,
	val slug: String,
	val branch: String,
	val baseBranch: String,
	val specPath: String?,
	val stepId: Long?,
	val worktree: FeatureWorktreeResponse?,
	/** Null when no pull request was opened, or GitHub couldn't be read ([pullError] then says why). */
	val pull: PullResponse?,
	val pullError: String?,
	val needsRestack: Boolean,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	companion object {
		fun from(view: TaskView) = TaskResponse(
			number = view.task.number,
			layer = view.task.layer,
			slug = view.task.slug,
			branch = view.task.branch,
			baseBranch = view.task.baseBranch,
			specPath = view.task.specPath,
			stepId = view.task.stepId,
			worktree = view.worktree?.let(FeatureWorktreeResponse::from),
			pull = view.pull?.let(PullResponse::from),
			pullError = view.pullError?.detail,
			needsRestack = view.needsRestack,
			createdAt = view.task.createdAt,
			updatedAt = view.task.updatedAt,
		)
	}
}

/** A task's pull request; [state] is `open`, `closed` or `merged`. */
data class PullResponse(
	val number: Int,
	val title: String,
	val state: String,
	val draft: Boolean,
	val htmlUrl: String,
	val baseRef: String,
	val headSha: String?,
	val mergedAt: Instant?,
	val checks: ChecksResponse,
) {
	companion object {
		fun from(pull: PullStatus) = PullResponse(
			number = pull.number,
			title = pull.title,
			state = pull.state,
			draft = pull.draft,
			htmlUrl = pull.htmlUrl,
			baseRef = pull.baseRef,
			headSha = pull.headSha,
			mergedAt = pull.mergedAt,
			checks = ChecksResponse.from(pull.checks),
		)
	}
}

/** The checks on a pull request's head: `none`, `pending`, `success` or `failure`. */
data class ChecksResponse(val state: String, val runs: List<CheckRunResponse>) {
	companion object {
		fun from(checks: ChecksStatus) = ChecksResponse(checks.state, checks.runs.map(CheckRunResponse::from))
	}
}

/** One check run. */
data class CheckRunResponse(
	val name: String,
	val status: String,
	val conclusion: String?,
	val htmlUrl: String?,
	val title: String?,
	val summary: String?,
) {
	companion object {
		fun from(run: CheckRunStatus) = CheckRunResponse(run.name, run.status, run.conclusion, run.htmlUrl, run.title, run.summary)
	}
}
