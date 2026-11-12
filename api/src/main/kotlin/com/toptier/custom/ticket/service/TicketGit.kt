package com.toptier.custom.ticket.service

import com.toptier.custom.checkout.domain.RepositoryCheckout
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.PushResult
import com.toptier.custom.checkout.service.WorktreeService
import com.toptier.custom.feature.config.FeatureProperties
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketEvent
import com.toptier.custom.ticket.domain.TicketRepository
import com.toptier.custom.ticket.exception.InvalidTicketRequestException
import com.toptier.custom.ticket.exception.NothingCommittedException
import com.toptier.custom.ticket.exception.TicketNotFoundException
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.WorkspaceMissingException
import org.springframework.stereotype.Service
import java.nio.file.Path

/** What a commit did: [committed] is false, with no [sha], when the worktree had nothing to commit. */
data class CommitResult(val committed: Boolean, val sha: String?)

/** A ticket's worktree and the checkout it belongs to. */
private data class TicketCheckout(val dir: Path, val worktreeId: Long, val checkout: RepositoryCheckout)

/**
 * Commits and pushes a ticket's branch from its worktree, the actions behind the page's Commit and Push buttons.
 * Each runs under the checkout's lock and only on the ticket's own branch, and writes one history entry on success.
 */
@Service
class TicketGit(
	private val projects: ProjectService,
	private val tickets: TicketRepository,
	private val history: TicketHistory,
	private val worktrees: TicketWorktrees,
	private val checkouts: RepositoryCheckoutRepository,
	private val worktreeService: WorktreeService,
	private val runner: GitRunner,
	private val locks: CheckoutLocks,
	private val props: FeatureProperties,
) {

	/**
	 * Commits everything in the worktree (`git add -A`) with [rawMessage], or `#<number> <title>` when it is blank.
	 * A clean worktree commits nothing and writes no entry. Throws [InvalidTicketRequestException] for a message
	 * over 200 characters and [WorkspaceMissingException] without a usable workspace.
	 */
	fun commit(projectId: Long, ticketId: Long, rawMessage: String?): CommitResult {
		val ticket = loadActive(projectId, ticketId)
		val message = rawMessage?.trim()?.takeIf { it.isNotEmpty() } ?: "#${ticket.number} ${ticket.title}".take(MESSAGE_MAX)
		if (message.length > MESSAGE_MAX) throw InvalidTicketRequestException("Use $MESSAGE_MAX characters or fewer for the message.")
		val target = target(ticket)
		return locks.withLock(target.checkout.id!!) {
			requireBranch(target.dir, ticket)
			if (runner.runOrThrow(target.dir, listOf("status", "--porcelain")).isBlank()) {
				return@withLock CommitResult(committed = false, sha = null)
			}
			runner.runOrThrow(target.dir, listOf("add", "-A"))
			runner.runOrThrow(
				target.dir,
				listOf(
					"-c", "user.name=${props.commitName}", "-c", "user.email=${props.commitEmail}",
					"-c", "commit.gpgsign=false", "commit", "-m", message,
				),
			)
			val sha = runner.runOrThrow(target.dir, listOf("rev-parse", "--short", "HEAD")).trim()
			history.record(ticket.id!!, TicketEvent.KIND_COMMIT, newValue = sha, detail = message)
			CommitResult(committed = true, sha = sha)
		}
	}

	/**
	 * Pushes the ticket's branch. Needs at least one commit beyond the default branch ([NothingCommittedException]).
	 * The git and push exceptions of the checkout package pass through, and a refused push writes no entry.
	 */
	fun push(projectId: Long, ticketId: Long): PushResult {
		val ticket = loadActive(projectId, ticketId)
		val target = target(ticket)
		val defaultBranch = target.checkout.defaultBranch!!
		return locks.withLock(target.checkout.id!!) {
			requireBranch(target.dir, ticket)
			val ahead = runner.runOrThrow(target.dir, listOf("rev-list", "--count", "refs/remotes/origin/$defaultBranch..HEAD")).trim()
			if ((ahead.toIntOrNull() ?: 0) == 0) throw NothingCommittedException()
			val pushed = worktreeService.push(projectId, target.worktreeId, force = false)
			history.record(ticket.id!!, TicketEvent.KIND_PUSH, newValue = pushed.head, detail = pushed.branch)
			pushed
		}
	}

	private fun loadActive(projectId: Long, ticketId: Long): Ticket {
		val project = projects.get(projectId)
		val ticket = tickets.findByIdAndProjectId(ticketId, projectId) ?: throw TicketNotFoundException()
		if (project.archived) throw TicketProjectArchivedException()
		return ticket
	}

	private fun target(ticket: Ticket): TicketCheckout {
		val branch = ticket.branch
		if (ticket.workspaceState != READY || branch == null) throw WorkspaceMissingException()
		val worktree = worktrees.find(ticket.projectId, branch)
		if (worktree == null || worktree.missing) {
			throw WorkspaceMissingException("The worktree for $branch is missing. Check it out again on the project page.")
		}
		val checkout = checkouts.findByProjectId(ticket.projectId) ?: throw WorkspaceMissingException()
		return TicketCheckout(Path.of(worktree.path), worktree.id, checkout)
	}

	/** Refuses to touch a worktree that has another branch checked out. */
	private fun requireBranch(dir: Path, ticket: Ticket) {
		val current = runner.runOrThrow(dir, listOf("rev-parse", "--abbrev-ref", "HEAD")).trim()
		if (current != ticket.branch) {
			throw WorkspaceMissingException("The worktree has $current checked out, not ${ticket.branch}. Nothing was changed.")
		}
	}

	private companion object {
		const val READY = "ready"
		const val MESSAGE_MAX = 200
	}
}
