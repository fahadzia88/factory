package com.toptier.custom.ticket.service

import com.toptier.custom.ticket.domain.Ticket

/** The title and body of a ticket's pull request. */
object TicketPullText {

	/** `#<number> <title>`, cut to 200 characters. */
	fun title(ticket: Ticket): String = "#${ticket.number} ${ticket.title}".take(TITLE_MAX)

	/** The ticket's description (or "No description."), its number and type, and a link to its `details.md`. */
	fun body(ticket: Ticket, detailsUrl: String): String =
		"${ticket.description ?: "No description."}\n\nTicket #${ticket.number} (${ticket.type.value})\n\ndetails.md: $detailsUrl\n"

	const val TITLE_MAX = 200
}
