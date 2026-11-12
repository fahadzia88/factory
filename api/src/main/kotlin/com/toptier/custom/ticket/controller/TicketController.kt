package com.toptier.custom.ticket.controller

import com.toptier.custom.ticket.domain.TicketStatus
import com.toptier.custom.ticket.dto.CreateTicketRequest
import com.toptier.custom.ticket.dto.SetTicketStatusRequest
import com.toptier.custom.ticket.dto.StatusInfoResponse
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
		return found.map { TicketResponse.from(it, archived) }
	}

	/** Files a ticket. */
	@PostMapping
	fun create(@PathVariable id: Long, @RequestBody request: CreateTicketRequest): ResponseEntity<TicketResponse> =
		ResponseEntity.status(HttpStatus.CREATED).body(TicketResponse.from(tickets.create(id, request)))

	/** Every status with its description and the ticket types that can have it. */
	@GetMapping("/statuses")
	fun statuses(@PathVariable id: Long): List<StatusInfoResponse> =
		tickets.statuses(id).map(StatusInfoResponse::from)

	/** One ticket. */
	@GetMapping("/{ticketId}")
	fun get(@PathVariable id: Long, @PathVariable ticketId: Long): TicketResponse =
		TicketResponse.from(tickets.get(id, ticketId), tickets.archived(id))

	/**
	 * Changes the fields present in the body. The body is read as a map so a missing field and a `null` one
	 * differ: `null` clears a description or a link.
	 */
	@PatchMapping("/{ticketId}")
	fun edit(
		@PathVariable id: Long,
		@PathVariable ticketId: Long,
		@RequestBody changes: Map<String, Any?>,
	): TicketResponse = TicketResponse.from(tickets.edit(id, ticketId, changes))

	/** Moves the ticket to another status, if the flow allows it. */
	@PostMapping("/{ticketId}/status")
	fun setStatus(
		@PathVariable id: Long,
		@PathVariable ticketId: Long,
		@RequestBody request: SetTicketStatusRequest,
	): TicketResponse = TicketResponse.from(tickets.changeStatus(id, ticketId, request.status))

	/** The ticket's history, oldest first. */
	@GetMapping("/{ticketId}/history")
	fun history(@PathVariable id: Long, @PathVariable ticketId: Long): List<TicketEventResponse> =
		tickets.history(id, ticketId).map(TicketEventResponse::from)
}
