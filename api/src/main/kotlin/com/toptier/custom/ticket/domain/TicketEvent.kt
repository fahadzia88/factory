package com.toptier.custom.ticket.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** One entry of a ticket's history. Entries are written once and never changed or deleted. */
@Entity
@Table(name = "ticket_event")
class TicketEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null

	/** A plain id of the [Ticket]. */
	@Column(name = "ticket_id", nullable = false)
	var ticketId: Long = 0

	/** One of the `KIND_` constants. */
	@Column(nullable = false, length = 20)
	var kind: String = ""

	/** The field a `status` or `edit` entry changed. */
	@Column(length = 40)
	var field: String? = null

	@Column(name = "old_value", columnDefinition = "TEXT")
	var oldValue: String? = null

	@Column(name = "new_value", columnDefinition = "TEXT")
	var newValue: String? = null

	@Column(columnDefinition = "TEXT")
	var detail: String? = null

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.EPOCH

	companion object {
		const val KIND_CREATED = "created"
		const val KIND_STATUS = "status"
		const val KIND_EDIT = "edit"
		const val KIND_WORKSPACE = "workspace"
		const val KIND_APPROVE = "approve"
		const val KIND_COMMIT = "commit"
		const val KIND_PUSH = "push"
		const val KIND_PULL = "pull"
	}
}
