package com.toptier.custom.ticket.service

import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketEvent
import com.toptier.custom.ticket.domain.TicketRepository
import com.toptier.custom.ticket.exception.AlreadyApprovedException
import com.toptier.custom.ticket.exception.NoFindingsException
import com.toptier.custom.ticket.exception.TicketNotFoundException
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.WorkspaceMissingException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/** A ticket's `details.md`: its path in the repository, its text, and whether the agent has written findings in it. */
data class TicketDetailsView(val path: String, val content: String, val hasFindings: Boolean)

/**
 * Reads a ticket's `details.md` straight from its worktree (uncommitted edits show) and approves the investigation.
 * The path comes from the ticket's stored folder, and the real path is checked to stay inside the worktree.
 */
@Service
class TicketDetails(
	private val projects: ProjectService,
	private val tickets: TicketRepository,
	private val history: TicketHistory,
	private val worktrees: TicketWorktrees,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	private val transaction = TransactionTemplate(transactionManager)

	/** The ticket's `details.md`. Throws [WorkspaceMissingException] without a ready workspace, a worktree or the file. */
	fun get(projectId: Long, ticketId: Long): TicketDetailsView {
		val ticket = load(projectId, ticketId)
		val file = locate(ticket)
		if (Files.size(file) > MAX_FILE_SIZE) throw WorkspaceMissingException("details.md is larger than 200 KB.")
		val content = String(Files.readAllBytes(file), Charsets.UTF_8)
		return TicketDetailsView("${ticket.folder}/$FILE", content, differs(ticket, content))
	}

	/**
	 * True when `details.md` differs from the template the workspace wrote, which means findings were added.
	 * False when there is no workspace, worktree or file to compare.
	 */
	fun hasFindings(ticket: Ticket): Boolean {
		return try {
			val file = locate(ticket)
			Files.size(file) > MAX_FILE_SIZE || differs(ticket, String(Files.readAllBytes(file), Charsets.UTF_8))
		} catch (_: WorkspaceMissingException) {
			false
		} catch (ex: IOException) {
			log.warn("Ticket {}: couldn't read {}: {}", ticket.id, FILE, ex.message)
			false
		}
	}

	/**
	 * Accepts the investigation: the ticket moves to `implementer` mode, where the agent may change code, and an
	 * `approve` entry is written. Needs a workspace and findings in `details.md`; the mode never goes back.
	 */
	fun approve(projectId: Long, ticketId: Long): Ticket = transaction.execute {
		val ticket = load(projectId, ticketId)
		if (projects.get(projectId).archived) throw TicketProjectArchivedException()
		locate(ticket)
		if (ticket.mode == IMPLEMENTER) throw AlreadyApprovedException()
		if (!hasFindings(ticket)) throw NoFindingsException()
		val now = clock.instant()
		ticket.mode = IMPLEMENTER
		ticket.approvedAt = now
		ticket.updatedAt = now
		tickets.save(ticket)
		history.record(ticket.id!!, TicketEvent.KIND_APPROVE, detail = "Investigation approved.")
		ticket
	}!!

	private fun load(projectId: Long, ticketId: Long): Ticket {
		projects.get(projectId)
		return tickets.findByIdAndProjectId(ticketId, projectId) ?: throw TicketNotFoundException()
	}

	private fun differs(ticket: Ticket, content: String): Boolean {
		val baseline = ticket.detailsBaseline ?: return false
		return TicketDetailsTemplate.sha256(content) != baseline
	}

	/** The real path of the ticket's `details.md`, inside its worktree. */
	private fun locate(ticket: Ticket): Path {
		val branch = ticket.branch
		val folder = ticket.folder
		if (ticket.workspaceState != READY || branch == null || folder == null) throw WorkspaceMissingException()
		val worktree = worktrees.find(ticket.projectId, branch)
		if (worktree == null || worktree.missing) {
			throw WorkspaceMissingException("The worktree for $branch is missing. Check it out again on the project page.")
		}
		val root = Path.of(worktree.path).toRealPath()
		val file = root.resolve(folder).resolve(FILE)
		if (!Files.exists(file)) throw WorkspaceMissingException("details.md doesn't exist in $folder.")
		val real = file.toRealPath()
		if (!real.startsWith(root) || !Files.isRegularFile(real)) {
			throw WorkspaceMissingException("details.md isn't a file inside the ticket's worktree.")
		}
		return real
	}

	private companion object {
		const val FILE = "details.md"
		const val READY = "ready"
		const val IMPLEMENTER = "implementer"
		const val MAX_FILE_SIZE = 200L * 1024
	}
}
