package com.toptier.custom.ticket.dto

import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketEvent
import java.time.Instant

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
		/** The ticket with the resting values of the fields later tasks fill in. */
		fun from(ticket: Ticket) = TicketResponse(
			id = ticket.id!!,
			projectId = ticket.projectId,
			number = ticket.number,
			type = ticket.type.value,
			title = ticket.title,
			description = ticket.description,
			priority = ticket.priority.value,
			status = ticket.status.value,
			statusDescription = ticket.status.description,
			allowedStatuses = emptyList(),
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
