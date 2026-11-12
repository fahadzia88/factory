package com.toptier.custom.ticket.exception

import com.toptier.custom.ticket.domain.TicketStatus

/** A ticket request that can't be carried out; the message is the response's `detail`. */
sealed class TicketException(message: String) : RuntimeException(message)

/** No ticket with that id in the project. */
class TicketNotFoundException : TicketException("Ticket not found.")

/** A ticket field is missing or invalid; the message names it. */
class InvalidTicketException(message: String) : TicketException(message)

/** A filter value in the request isn't one the tracker knows; the message names it. */
class InvalidTicketRequestException(message: String) : TicketException(message)

/** The request tried to change the ticket's type, which is fixed when the ticket is created. */
class TicketTypeFixedException : TicketException("A ticket's type can't be changed after it is created.")

/** The ticket can't move from [from] to [to]; [allowed] are the statuses it can move to. */
class InvalidTransitionException(val from: TicketStatus, val to: TicketStatus, val allowed: List<TicketStatus>) :
	TicketException(
		"A ticket that is ${from.value} can't move to ${to.value}. " +
			if (allowed.isEmpty()) "It has no further status." else "It can move to: ${allowed.joinToString(", ") { it.value }}.",
	)

/** The project is archived, so its tickets can be read but not changed. */
class TicketProjectArchivedException : TicketException("This project is archived. Restore it to change its tickets.")
