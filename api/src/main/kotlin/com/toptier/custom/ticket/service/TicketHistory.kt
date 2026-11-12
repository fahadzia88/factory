package com.toptier.custom.ticket.service

import com.toptier.custom.ticket.domain.TicketEvent
import com.toptier.custom.ticket.domain.TicketEventRepository
import org.springframework.stereotype.Service
import java.time.Clock

/** Writes a ticket's history. Callers run it in the same transaction as the change it records. */
@Service
class TicketHistory(
	private val events: TicketEventRepository,
	private val clock: Clock,
) {

	/** Saves one entry of [kind] for the ticket, stamped with the current time. */
	fun record(
		ticketId: Long,
		kind: String,
		field: String? = null,
		oldValue: String? = null,
		newValue: String? = null,
		detail: String? = null,
	): TicketEvent = events.save(TicketEvent().apply {
		this.ticketId = ticketId
		this.kind = kind
		this.field = field
		this.oldValue = oldValue
		this.newValue = newValue
		this.detail = detail
		createdAt = clock.instant()
	})
}
