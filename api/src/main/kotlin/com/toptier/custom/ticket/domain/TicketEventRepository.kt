package com.toptier.custom.ticket.domain

import org.springframework.data.jpa.repository.JpaRepository

/** Stores [TicketEvent]s. */
interface TicketEventRepository : JpaRepository<TicketEvent, Long> {

	/** A ticket's history, oldest first. */
	fun findByTicketIdOrderByCreatedAtAscIdAsc(ticketId: Long): List<TicketEvent>
}
