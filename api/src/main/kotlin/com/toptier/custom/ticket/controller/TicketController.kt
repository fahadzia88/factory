package com.toptier.custom.ticket.controller

import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.dto.CommitTicketRequest
import com.toptier.custom.ticket.dto.CommitTicketResponse
import com.toptier.custom.ticket.dto.CreateTicketRequest
import com.toptier.custom.ticket.dto.PushTicketResponse
import com.toptier.custom.ticket.dto.SetTicketStatusRequest
import com.toptier.custom.ticket.dto.StatusInfoResponse
import com.toptier.custom.ticket.dto.TicketDetailsResponse
import com.toptier.custom.ticket.dto.TicketEventResponse
import com.toptier.custom.ticket.dto.TicketResponse
import com.toptier.custom.ticket.service.TicketService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** A project's bug and improvement tickets. There is no delete, and a ticket's type can't be changed. */
@RestController
@RequestMapping("/api/projects/{id}/tickets")
class TicketController(
	private val tickets: TicketService,
) {

	/** The project's tickets, newest number first, optionally filtered by `type` and `status`. */
	@GetMapping
	fun list(
		@PathVariable id: Long,
		@RequestParam(required = false) type: String?,
		@RequestParam(required = false) status: String?,
	): List<TicketResponse> {
		val found = tickets.list(id, type, status)
		val archived = tickets.archived(id)
		return found.map { respond(it, archived) }
	}

	/** Files a ticket. */
	@PostMapping
	fun create(@PathVariable id: Long, @RequestBody request: CreateTicketRequest): ResponseEntity<TicketResponse> =
		ResponseEntity.status(HttpStatus.CREATED).body(respond(tickets.create(id, request)))

	/** Every status with its description and the ticket types that can have it. */
	@GetMapping("/statuses")
	fun statuses(@PathVariable id: Long): List<StatusInfoResponse> =
		tickets.statuses(id).map(StatusInfoResponse::from)

	/** One ticket. */
	@GetMapping("/{ticketId}")
	fun get(@PathVariable id: Long, @PathVariable ticketId: Long): TicketResponse =
		respond(tickets.get(id, ticketId), tickets.archived(id))

	/**
	 * Changes the fields present in the body. The body is read as a map so a missing field and a `null` one
	 * differ: `null` clears a description or a link.
	 */
	@PatchMapping("/{ticketId}")
	fun edit(
		@PathVariable id: Long,
		@PathVariable ticketId: Long,
		@RequestBody changes: Map<String, Any?>,
	): TicketResponse = respond(tickets.edit(id, ticketId, changes))

	/** Moves the ticket to another status, if the flow allows it. The first `in_progress` builds its workspace. */
	@PostMapping("/{ticketId}/status")
	fun setStatus(
		@PathVariable id: Long,
		@PathVariable ticketId: Long,
		@RequestBody request: SetTicketStatusRequest,
	): TicketResponse = respond(tickets.changeStatus(id, ticketId, request.status))

	/** Builds the rest of a workspace that failed, without repeating what is done. */
	@PostMapping("/{ticketId}/workspace")
	fun retryWorkspace(@PathVariable id: Long, @PathVariable ticketId: Long): TicketResponse =
		respond(tickets.retryWorkspace(id, ticketId))

	/** The ticket's `details.md`, read from its worktree. */
	@GetMapping("/{ticketId}/details")
	fun details(@PathVariable id: Long, @PathVariable ticketId: Long): TicketDetailsResponse =
		TicketDetailsResponse.from(tickets.details(id, ticketId))

	/** Accepts the investigation, which lets the agent change code. */
	@PostMapping("/{ticketId}/approve")
	fun approve(@PathVariable id: Long, @PathVariable ticketId: Long): TicketResponse =
		respond(tickets.approve(id, ticketId))

	/** Commits everything in the ticket's worktree; with nothing to commit, answers `committed: false`. */
	@PostMapping("/{ticketId}/commit")
	fun commit(
		@PathVariable id: Long,
		@PathVariable ticketId: Long,
		@RequestBody(required = false) request: CommitTicketRequest?,
	): CommitTicketResponse = tickets.commit(id, ticketId, request?.message).let { CommitTicketResponse(it.committed, it.sha) }

	/** Pushes the ticket's branch to GitHub. */
	@PostMapping("/{ticketId}/push")
	fun push(@PathVariable id: Long, @PathVariable ticketId: Long): PushTicketResponse =
		tickets.push(id, ticketId).let { PushTicketResponse(it.branch, it.head) }

	/** The ticket's history, oldest first. */
	@GetMapping("/{ticketId}/history")
	fun history(@PathVariable id: Long, @PathVariable ticketId: Long): List<TicketEventResponse> =
		tickets.history(id, ticketId).map(TicketEventResponse::from)

	/** The ticket as JSON; changes only reach active projects, so [archived] is true only when reading. */
	private fun respond(ticket: Ticket, archived: Boolean = false): TicketResponse =
		TicketResponse.from(ticket, archived, tickets.worktreeOf(ticket), tickets.hasFindings(ticket))
}
