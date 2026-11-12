package com.toptier.custom.ticket.domain

import org.springframework.data.jpa.repository.JpaRepository

/** Stores [TicketMessage]s. */
interface TicketMessageRepository : JpaRepository<TicketMessage, Long> {

	/** A ticket's chat, oldest first. */
	fun findByTicketIdOrderByCreatedAtAscIdAsc(ticketId: Long): List<TicketMessage>
}
