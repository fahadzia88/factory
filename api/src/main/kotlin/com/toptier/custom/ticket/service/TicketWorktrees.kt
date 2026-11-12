package com.toptier.custom.ticket.service

import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.checkout.service.WorktreeInspector
import org.springframework.stereotype.Service
import java.nio.file.Path

/** The worktree of a ticket's branch; [missing] when its folder isn't on disk. */
data class TicketWorktree(val id: Long, val path: String, val missing: Boolean)

/** Finds a ticket's worktree by its branch in the project's ready checkout. */
@Service
class TicketWorktrees(
	private val checkouts: RepositoryCheckoutRepository,
	private val worktrees: RepositoryWorktreeRepository,
	private val inspector: WorktreeInspector,
) {

	/** The worktree of [branch] in the project's ready checkout, or null when there's none. */
	fun find(projectId: Long, branch: String): TicketWorktree? {
		val checkout = checkouts.findByProjectId(projectId)?.takeIf { it.state == CheckoutState.READY } ?: return null
		val row = worktrees.findByCheckoutIdAndBranch(checkout.id!!, branch) ?: return null
		return TicketWorktree(row.id!!, row.path, inspector.missing(Path.of(row.path)))
	}
}
