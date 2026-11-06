package com.toptier.custom.checkout.dto

import com.toptier.custom.checkout.service.CheckoutView
import com.toptier.custom.checkout.service.HeadCommit
import com.toptier.custom.checkout.service.WorktreeView
import java.time.Instant

/** A project's checkout as the API returns it; `worktrees` is empty unless `state` is `ready`. */
data class CheckoutResponse(
	val id: Long,
	val projectId: Long,
	/** `cloning`, `ready` or `failed`. */
	val state: String,
	/** Why the clone failed, in plain words; null unless failed. */
	val error: String?,
	val path: String,
	val defaultBranch: String?,
	val createdAt: Instant,
	val lastFetchedAt: Instant?,
	/** The main worktree first, then oldest first. */
	val worktrees: List<WorktreeResponse>,
) {
	companion object {
		fun from(view: CheckoutView): CheckoutResponse {
			val checkout = view.checkout
			return CheckoutResponse(
				id = requireNotNull(checkout.id) { "Checkout has not been saved" },
				projectId = checkout.projectId,
				state = checkout.state.json,
				error = checkout.error,
				path = checkout.path,
				defaultBranch = checkout.defaultBranch,
				createdAt = checkout.createdAt,
				lastFetchedAt = checkout.lastFetchedAt,
				worktrees = view.worktrees.map(WorktreeResponse::from),
			)
		}
	}
}

/** A worktree of a checkout; `head` is null when the folder is `missing`. */
data class WorktreeResponse(
	val id: Long,
	val branch: String,
	val path: String,
	val main: Boolean,
	/** The branch a new feature branch was created from; null for tracked branches and the main worktree. */
	val baseBranch: String?,
	/** True when the folder (or its `.git`) isn't on disk. */
	val missing: Boolean,
	val head: HeadResponse?,
	val createdAt: Instant,
) {
	companion object {
		fun from(view: WorktreeView): WorktreeResponse {
			val worktree = view.worktree
			return WorktreeResponse(
				id = requireNotNull(worktree.id) { "Worktree has not been saved" },
				branch = worktree.branch,
				path = worktree.path,
				main = worktree.main,
				baseBranch = worktree.baseBranch,
				missing = view.missing,
				head = view.head?.let(HeadResponse::from),
				createdAt = worktree.createdAt,
			)
		}
	}
}

/** The last commit of a worktree's branch. */
data class HeadResponse(val sha: String, val subject: String) {
	companion object {
		fun from(head: HeadCommit) = HeadResponse(head.sha, head.subject)
	}
}
