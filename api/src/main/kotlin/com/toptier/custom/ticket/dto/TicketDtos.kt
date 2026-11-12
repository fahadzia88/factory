package com.toptier.custom.ticket.dto

import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketEvent
import com.toptier.custom.ticket.domain.TicketStatus
import com.toptier.custom.ticket.domain.TicketType
import com.toptier.custom.ticket.service.TicketStatusRules
import java.time.Instant

/** Body of `POST …/tickets/{ticketId}/status`; [status] is a JSON status value like `in_progress`. */
data class SetTicketStatusRequest(val status: String? = null)

/** A status with its description and the ticket types that can have it. */
data class StatusInfoResponse(val status: String, val description: String, val types: List<String>) {
	companion object {
		fun from(status: TicketStatus) = StatusInfoResponse(
			status = status.value,
			description = status.description,
			types = TicketType.entries.filter { status.usableBy(it) }.map { it.value },
		)
	}
}

/** Body of `POST /api/projects/{id}/tickets`; [type] is `bug` or `improvement`, [priority] defaults to `medium`. */
data class CreateTicketRequest(
	val type: String? = null,
	val title: String? = null,
	val description: String? = null,
	val priority: String? = null,
)

/** The worktree of a ticket's branch; [missing] when its folder isn't on disk. */
data class TicketWorktreeResponse(val id: Long, val path: String, val missing: Boolean)

/** A ticket's workspace: [state] is `none`, `ready` or `failed`; the rest is empty until the first `in_progress`. */
data class TicketWorkspaceResponse(
	val state: String,
	val error: String?,
	val branch: String?,
	val folder: String?,
	val worktree: TicketWorktreeResponse?,
)

/** A ticket with its status description, workspace and investigation mode. */
data class TicketResponse(
	val id: Long,
	val projectId: Long,
	val number: Int,
	val type: String,
	val title: String,
	val description: String?,
	val priority: String,
	val status: String,
	val statusDescription: String,
	val allowedStatuses: List<String>,
	val pullRequestUrl: String?,
	val pullNumber: Int?,
	val workspace: TicketWorkspaceResponse,
	val mode: String,
	val approvedAt: Instant?,
	val hasFindings: Boolean,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	companion object {
		/**
		 * The ticket with the resting values of the fields later tasks fill in. An [archived] project's ticket
		 * has no allowed statuses.
		 */
		fun from(ticket: Ticket, archived: Boolean = false) = TicketResponse(
			id = ticket.id!!,
			projectId = ticket.projectId,
			number = ticket.number,
			type = ticket.type.value,
			title = ticket.title,
			description = ticket.description,
			priority = ticket.priority.value,
			status = ticket.status.value,
			statusDescription = ticket.status.description,
			allowedStatuses = if (archived) emptyList() else TicketStatusRules.allowed(ticket.type, ticket.status).map { it.value },
			pullRequestUrl = ticket.pullRequestUrl,
			pullNumber = ticket.pullNumber,
			workspace = TicketWorkspaceResponse(
				state = ticket.workspaceState,
				error = ticket.workspaceError,
				branch = ticket.branch,
				folder = ticket.folder,
				worktree = null,
			),
			mode = ticket.mode,
			approvedAt = ticket.approvedAt,
			hasFindings = false,
			createdAt = ticket.createdAt,
			updatedAt = ticket.updatedAt,
		)
	}
}

/** One entry of a ticket's history. */
data class TicketEventResponse(
	val id: Long,
	val kind: String,
	val field: String?,
	val oldValue: String?,
	val newValue: String?,
	val detail: String?,
	val createdAt: Instant,
) {
	companion object {
		fun from(event: TicketEvent) = TicketEventResponse(
			id = event.id!!,
			kind = event.kind,
			field = event.field,
			oldValue = event.oldValue,
			newValue = event.newValue,
			detail = event.detail,
			createdAt = event.createdAt,
		)
	}
}
