package com.toptier.custom.ticket.service

import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketEvent
import com.toptier.custom.ticket.domain.TicketEventRepository
import com.toptier.custom.ticket.domain.TicketPriority
import com.toptier.custom.ticket.domain.TicketRepository
import com.toptier.custom.ticket.domain.TicketStatus
import com.toptier.custom.ticket.domain.TicketType
import com.toptier.custom.ticket.dto.CreateTicketRequest
import com.toptier.custom.ticket.exception.InvalidTicketException
import com.toptier.custom.ticket.exception.InvalidTicketRequestException
import com.toptier.custom.ticket.exception.TicketNotFoundException
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.TicketTypeFixedException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URISyntaxException
import java.time.Clock

/**
 * A project's tickets: create, list, read, edit and history. Every change writes its history entries in the
 * same transaction, and a request that is refused writes nothing. Tickets of an archived project can be read
 * but not changed.
 */
@Service
class TicketService(
	private val projects: ProjectService,
	private val tickets: TicketRepository,
	private val events: TicketEventRepository,
	private val history: TicketHistory,
	private val jsonMapper: JsonMapper,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {

	private val transaction = TransactionTemplate(transactionManager)

	/**
	 * Files a ticket as `filed` with the project's next number. Two creates at once can pick the same number;
	 * the loser's insert is refused by the unique key and tried once more, in a new transaction.
	 */
	fun create(projectId: Long, request: CreateTicketRequest): Ticket {
		requireActive(projectId)
		val type = TicketType.fromValue(request.type) ?: throw InvalidTicketException("Choose a type: bug or improvement.")
		val title = validTitle(request.title)
		val description = validDescription(request.description)
		val priority = request.priority?.trim()?.takeIf { it.isNotEmpty() }
			?.let { TicketPriority.fromValue(it) ?: throw InvalidTicketException(PRIORITY_CHOICES) }
			?: TicketPriority.MEDIUM
		var attempt = 0
		while (true) {
			try {
				return transaction.execute { insert(projectId, type, title, description, priority) }!!
			} catch (ex: DataIntegrityViolationException) {
				if (++attempt >= 2) throw ex
			}
		}
	}

	/** The project's tickets, newest number first, optionally only those of [type] and [status] (JSON values). */
	@Transactional(readOnly = true)
	fun list(projectId: Long, type: String?, status: String?): List<Ticket> {
		projects.get(projectId)
		val wantedType = type?.let { TicketType.fromValue(it) ?: throw InvalidTicketRequestException("\"$it\" isn't a ticket type.") }
		val wantedStatus = status?.let { TicketStatus.fromValue(it) ?: throw InvalidTicketRequestException("\"$it\" isn't a ticket status.") }
		return tickets.findByProjectIdOrderByNumberDesc(projectId).filter {
			(wantedType == null || it.type == wantedType) && (wantedStatus == null || it.status == wantedStatus)
		}
	}

	/** One ticket of the project; another project's ticket is not found too. */
	@Transactional(readOnly = true)
	fun get(projectId: Long, ticketId: Long): Ticket {
		projects.get(projectId)
		return tickets.findByIdAndProjectId(ticketId, projectId) ?: throw TicketNotFoundException()
	}

	/**
	 * Changes `title`, `description`, `priority` and `pullRequestUrl`, given as the keys present in [changes]
	 * (`null` clears a description or a link). Everything is checked first; then one `edit` entry is written for
	 * each field whose value really changed, and none when nothing did.
	 */
	@Transactional
	fun edit(projectId: Long, ticketId: Long, changes: Map<String, Any?>): Ticket {
		val ticket = get(projectId, ticketId)
		requireActive(projectId)
		if ("type" in changes) throw TicketTypeFixedException()
		changes.keys.firstOrNull { it !in EDITABLE }?.let { throw InvalidTicketException("\"$it\" can't be changed.") }

		val title = if ("title" in changes) validTitle(text("title", changes["title"])) else ticket.title
		val description = if ("description" in changes) validDescription(text("description", changes["description"])) else ticket.description
		val priority = if ("priority" in changes) {
			TicketPriority.fromValue(text("priority", changes["priority"])) ?: throw InvalidTicketException(PRIORITY_CHOICES)
		} else {
			ticket.priority
		}
		val link = if ("pullRequestUrl" in changes) validLink(text("pullRequestUrl", changes["pullRequestUrl"])) else ticket.pullRequestUrl

		var changed = false
		fun change(field: String, old: String?, new: String?) {
			if (old == new) return
			history.record(ticket.id!!, TicketEvent.KIND_EDIT, field, old, new)
			changed = true
		}
		change("title", ticket.title, title)
		change("description", ticket.description, description)
		change("priority", ticket.priority.value, priority.value)
		change("pullRequestUrl", ticket.pullRequestUrl, link)
		if (!changed) return ticket

		ticket.title = title
		ticket.description = description
		ticket.priority = priority
		ticket.pullRequestUrl = link
		ticket.updatedAt = clock.instant()
		return tickets.save(ticket)
	}

	/** The ticket's history, oldest first. */
	@Transactional(readOnly = true)
	fun history(projectId: Long, ticketId: Long): List<TicketEvent> {
		val ticket = get(projectId, ticketId)
		return events.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.id!!)
	}

	private fun insert(projectId: Long, type: TicketType, title: String, description: String?, priority: TicketPriority): Ticket {
		val now = clock.instant()
		val ticket = tickets.saveAndFlush(Ticket().apply {
			this.projectId = projectId
			number = (tickets.maxNumber(projectId) ?: 0) + 1
			this.type = type
			this.title = title
			this.description = description
			this.priority = priority
			status = TicketStatus.FILED
			createdAt = now
			updatedAt = now
		})
		val initial = linkedMapOf(
			"type" to type.value,
			"title" to title,
			"description" to description,
			"priority" to priority.value,
			"status" to TicketStatus.FILED.value,
		)
		history.record(ticket.id!!, TicketEvent.KIND_CREATED, newValue = jsonMapper.writeValueAsString(initial))
		return ticket
	}

	/** Refuses a change to an archived project's tickets; throws `ProjectNotFoundException` for an unknown project. */
	private fun requireActive(projectId: Long) {
		if (projects.get(projectId).archived) throw TicketProjectArchivedException()
	}

	/** [value] as text, or null when it is null; anything else is refused naming [field]. */
	private fun text(field: String, value: Any?): String? {
		if (value == null) return null
		return value as? String ?: throw InvalidTicketException("$field must be text.")
	}

	private fun validTitle(raw: String?): String {
		val title = raw?.trim().orEmpty()
		return when {
			title.isEmpty() -> throw InvalidTicketException("Enter a title.")
			title.length > TITLE_MAX -> throw InvalidTicketException("Use $TITLE_MAX characters or fewer for the title.")
			else -> title
		}
	}

	private fun validDescription(raw: String?): String? {
		val description = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		if (description.length > DESCRIPTION_MAX) throw InvalidTicketException("Use $DESCRIPTION_MAX characters or fewer for the description.")
		return description
	}

	/** A pull request link: blank clears it, anything else must be an http(s) URL with a host. */
	private fun validLink(raw: String?): String? {
		val link = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val uri = try {
			URI(link)
		} catch (_: URISyntaxException) {
			null
		}
		val scheme = uri?.scheme?.lowercase()
		if (uri == null || (scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty() || link.length > LINK_MAX) {
			throw InvalidTicketException("Use a link starting with http:// or https://, up to $LINK_MAX characters.")
		}
		return link
	}

	private companion object {
		const val TITLE_MAX = 200
		const val DESCRIPTION_MAX = 10_000
		const val LINK_MAX = 500
		const val PRIORITY_CHOICES = "Choose a priority: low, medium, high or critical."
		val EDITABLE = setOf("title", "description", "priority", "pullRequestUrl")
	}
}
