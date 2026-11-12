package com.toptier.custom.ticket.service

import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.feature.service.PullText
import com.toptier.custom.github.service.GitHubPullWriteClient
import com.toptier.custom.project.service.GitHubRepositoryUrl
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketEvent
import com.toptier.custom.ticket.domain.TicketRepository
import com.toptier.custom.ticket.exception.BranchNotPushedException
import com.toptier.custom.ticket.exception.InvalidTicketRequestException
import com.toptier.custom.ticket.exception.NothingToCompareException
import com.toptier.custom.ticket.exception.PullLinkSetException
import com.toptier.custom.ticket.exception.TicketNotFoundException
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.TicketPullExistsException
import com.toptier.custom.ticket.exception.WorkspaceMissingException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.time.Clock

/**
 * Opens the pull request of a ticket's pushed branch into the default branch and keeps its link on the ticket.
 * Nothing is saved when GitHub refuses, and the ticket's status is never changed.
 */
@Service
class TicketPulls(
	private val projects: ProjectService,
	private val tickets: TicketRepository,
	private val history: TicketHistory,
	private val worktrees: TicketWorktrees,
	private val checkouts: RepositoryCheckoutRepository,
	private val checkoutService: CheckoutService,
	private val locks: CheckoutLocks,
	private val runner: GitRunner,
	private val pulls: GitHubPullWriteClient,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {

	private val transaction = TransactionTemplate(transactionManager)

	/**
	 * Opens the pull request, with [rawTitle] or `#<number> <title>`. Refuses, in this order: an archived project,
	 * a ticket that has one ([TicketPullExistsException]), a hand-typed link when [replaceLink] is false
	 * ([PullLinkSetException]), a missing workspace, a branch that isn't on GitHub ([BranchNotPushedException]) and
	 * a branch with no commit beyond the default branch ([NothingToCompareException]). GitHub failures pass through.
	 */
	fun open(projectId: Long, ticketId: Long, rawTitle: String?, replaceLink: Boolean): Ticket {
		val project = projects.get(projectId)
		val ticket = tickets.findByIdAndProjectId(ticketId, projectId) ?: throw TicketNotFoundException()
		if (project.archived) throw TicketProjectArchivedException()
		if (ticket.pullNumber != null) throw TicketPullExistsException()
		if (ticket.pullRequestUrl != null && !replaceLink) throw PullLinkSetException()
		val branch = ticket.branch
		val folder = ticket.folder
		if (ticket.workspaceState != READY || branch == null || folder == null) throw WorkspaceMissingException()
		val worktree = worktrees.find(projectId, branch)
		if (worktree == null || worktree.missing) {
			throw WorkspaceMissingException("The worktree for $branch is missing. Check it out again on the project page.")
		}
		val title = rawTitle?.trim()?.takeIf { it.isNotEmpty() } ?: TicketPullText.title(ticket)
		if (title.length > TicketPullText.TITLE_MAX) {
			throw InvalidTicketRequestException("Use ${TicketPullText.TITLE_MAX} characters or fewer for the title.")
		}
		val checkout = checkouts.findByProjectId(projectId) ?: throw CheckoutNotReadyException()
		val defaultBranch = checkout.defaultBranch!!

		locks.withLock(checkout.id!!) {
			checkoutService.fetchUnderLock(checkout)
			val clone = Path.of(checkout.path)
			if (!runner.refExists(clone, "refs/remotes/origin/$branch")) throw BranchNotPushedException(branch)
			val ahead = runner.runOrThrow(
				clone,
				listOf("rev-list", "--count", "refs/remotes/origin/$defaultBranch..refs/remotes/origin/$branch"),
			).trim()
			if ((ahead.toIntOrNull() ?: 0) == 0) throw NothingToCompareException()
		}

		val repository = checkNotNull(GitHubRepositoryUrl.parse(project.repositoryUrl)) {
			"Stored URL of project $projectId is invalid"
		}
		val body = TicketPullText.body(ticket, PullText.blobUrl(repository.owner, repository.repo, branch, "$folder/details.md"))
		val created = pulls.create(repository.owner, repository.repo, title, branch, defaultBranch, body)

		return transaction.execute {
			val stored = tickets.findById(ticketId).orElseThrow { TicketNotFoundException() }
			val replaced = stored.pullRequestUrl
			stored.pullNumber = created.number
			stored.pullRequestUrl = created.htmlUrl
			stored.updatedAt = clock.instant()
			tickets.save(stored)
			history.record(stored.id!!, TicketEvent.KIND_PULL, oldValue = replaced, newValue = created.htmlUrl, detail = title)
			stored
		}!!
	}

	private companion object {
		const val READY = "ready"
	}
}
