package com.toptier.custom.checkout.dto

import com.toptier.custom.checkout.service.HeadCommit
import com.toptier.custom.checkout.service.PushResult
import com.toptier.custom.checkout.service.UnsavedWork

/** Body of `POST …/checkout/worktrees`; [base] defaults to the checkout's default branch. */
data class AddWorktreeRequest(val branch: String? = null, val base: String? = null)

/** Body of `POST …/worktrees/{worktreeId}/push`; a missing body or [force] means a plain push. */
data class PushRequest(val force: Boolean? = false)

/** A pushed worktree: its branch and the short SHA of its head. */
data class PushResponse(val branch: String, val head: String) {
	companion object {
		fun from(result: PushResult) = PushResponse(result.branch, result.head)
	}
}

/** A worktree's work that isn't on GitHub. */
data class UnsavedWorkResponse(
	val worktreeId: Long,
	val branch: String,
	/** Changed and untracked files, at most 50. */
	val changedFiles: List<String>,
	/** Commits on no `origin` branch, newest first, at most 50. */
	val unpushedCommits: List<CommitResponse>,
) {
	companion object {
		fun from(unsaved: UnsavedWork) = UnsavedWorkResponse(
			worktreeId = unsaved.worktreeId,
			branch = unsaved.branch,
			changedFiles = unsaved.changedFiles,
			unpushedCommits = unsaved.unpushedCommits.map(CommitResponse::from),
		)
	}
}

/** One commit: its short SHA and subject. */
data class CommitResponse(val sha: String, val subject: String) {
	companion object {
		fun from(commit: HeadCommit) = CommitResponse(commit.sha, commit.subject)
	}
}
