package com.toptier.custom.ticket.service

import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.checkout.exception.GitCommandException
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeService
import com.toptier.custom.feature.config.FeatureProperties
import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketEvent
import com.toptier.custom.ticket.domain.TicketRepository
import com.toptier.custom.ticket.exception.TicketNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** A workspace step that can't continue; the message is kept on the ticket. */
private class WorkspaceStepException(message: String) : RuntimeException(message)

/**
 * Makes a ticket's workspace: a worktree and branch from the default branch, `<folder>/details.md` written from
 * the template and committed, and the branch pushed. Each step is skipped when it is already done, so running
 * it again after a failure finishes the job without a second worktree, commit or push. Results are recorded
 * in the ticket's history; a failure is kept on the ticket and never thrown.
 */
@Service
class TicketWorkspace(
	private val tickets: TicketRepository,
	private val history: TicketHistory,
	private val checkouts: RepositoryCheckoutRepository,
	private val worktrees: TicketWorktrees,
	private val worktreeService: WorktreeService,
	private val runner: GitRunner,
	private val locks: CheckoutLocks,
	private val props: FeatureProperties,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	private val transaction = TransactionTemplate(transactionManager)

	/** One run per ticket at a time. */
	private val ticketLocks = ConcurrentHashMap<Long, ReentrantLock>()

	/** Builds what is missing of the ticket's workspace; leaves it `ready`, or `failed` with the reason. */
	fun ensure(ticketId: Long) {
		val lock = ticketLocks.computeIfAbsent(ticketId) { ReentrantLock() }
		lock.lock()
		try {
			build(ticketId)
		} finally {
			lock.unlock()
		}
	}

	private fun build(ticketId: Long) {
		val first = tickets.findById(ticketId).orElseThrow { TicketNotFoundException() }
		if (first.workspaceState == READY) return
		var step = "preparing"
		try {
			val ticket = named(first)
			val checkout = checkouts.findByProjectId(ticket.projectId)?.takeIf { it.state == CheckoutState.READY }
				?: throw CheckoutNotReadyException()
			runner.requireToken()
			locks.withLock(checkout.id!!) {
				step = "worktree"
				val worktree = worktree(ticket, checkout.defaultBranch!!)
				step = "details"
				writeDetails(ticket, worktree)
				step = "push"
				push(ticket, worktree)
			}
			update(ticketId) {
				workspaceState = READY
				workspaceError = null
			}
		} catch (ex: Exception) {
			fail(ticketId, step, ex)
		}
	}

	/** Gives the ticket its slug, branch and folder the first time; later runs reuse them. */
	private fun named(ticket: Ticket): Ticket {
		if (ticket.slug != null) return ticket
		return update(ticket.id!!) {
			val slug = TicketNaming.slug(title)
			this.slug = slug
			branch = TicketNaming.branch(type, number, slug)
			folder = TicketNaming.folder(type, number, slug)
		}
	}

	private fun worktree(ticket: Ticket, defaultBranch: String): TicketWorktree {
		val branch = ticket.branch!!
		worktrees.find(ticket.projectId, branch)?.let {
			if (it.missing) throw WorkspaceStepException("The worktree for $branch is missing. Check it out again on the project page.")
			return it
		}
		val added = worktreeService.add(ticket.projectId, branch, defaultBranch).worktree
		record(ticket, "worktree", "Worktree added on $branch.")
		return TicketWorktree(added.id!!, added.path, false)
	}

	private fun writeDetails(ticket: Ticket, worktree: TicketWorktree) {
		val dir = Path.of(worktree.path)
		val folder = ticket.folder!!
		val file = dir.resolve(folder).resolve(DETAILS)
		if (!Files.exists(file)) {
			val rendered = TicketDetailsTemplate.render(ticket)
			Files.createDirectories(file.parent)
			Files.writeString(file, rendered.text)
			update(ticket.id!!) { detailsBaseline = rendered.sha256 }
		}
		if (runner.runOrThrow(dir, listOf("status", "--porcelain", "--", folder)).isNotBlank()) {
			runner.runOrThrow(dir, listOf("add", "--", folder))
			runner.runOrThrow(
				dir,
				listOf(
					"-c", "user.name=${props.commitName}", "-c", "user.email=${props.commitEmail}",
					"-c", "commit.gpgsign=false", "commit", "-m", "#${ticket.number} ${ticket.title.take(100)}: details",
				),
			)
			record(ticket, "details", "$DETAILS committed in $folder.")
		}
	}

	private fun push(ticket: Ticket, worktree: TicketWorktree) {
		val dir = Path.of(worktree.path)
		val branch = ticket.branch!!
		val head = runner.runOrThrow(dir, listOf("rev-parse", "HEAD")).trim()
		val remote = runner.run(dir, listOf("rev-parse", "--verify", "--quiet", "refs/remotes/origin/$branch"))
		if (remote.exitCode == 0 && remote.stdout.trim() == head) return
		worktreeService.push(ticket.projectId, worktree.id, force = false)
		record(ticket, "push", "Branch $branch pushed.")
	}

	private fun fail(ticketId: Long, step: String, ex: Exception) {
		log.warn("Ticket {}: the workspace failed at {}: {}", ticketId, step, ex.message)
		val message = when (ex) {
			is GitCommandException -> "A git command failed on the server."
			else -> ex.message ?: ex.javaClass.simpleName
		}
		transaction.executeWithoutResult {
			update(ticketId) {
				workspaceState = FAILED
				workspaceError = message
			}
			history.record(ticketId, TicketEvent.KIND_WORKSPACE, step, detail = "Failed: $message")
		}
	}

	private fun record(ticket: Ticket, step: String, detail: String) {
		history.record(ticket.id!!, TicketEvent.KIND_WORKSPACE, step, detail = detail)
	}

	/** Applies [change] to the stored ticket in its own transaction and returns the saved ticket. */
	private fun update(ticketId: Long, change: Ticket.() -> Unit): Ticket = transaction.execute {
		val ticket = tickets.findById(ticketId).orElseThrow { TicketNotFoundException() }
		ticket.change()
		ticket.updatedAt = clock.instant()
		tickets.save(ticket)
	}!!

	private companion object {
		const val READY = "ready"
		const val FAILED = "failed"
		const val DETAILS = "details.md"
	}
}
