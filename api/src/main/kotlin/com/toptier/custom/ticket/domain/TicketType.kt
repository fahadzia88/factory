package com.toptier.custom.ticket.domain

/** What a ticket asks for: a fix for something broken, or a change to something that works. */
enum class TicketType(val value: String) {
	BUG("bug"),
	IMPROVEMENT("improvement"),
	;

	companion object {
		/** The type whose JSON [value] is [raw], else null. */
		fun fromValue(raw: String?): TicketType? = entries.firstOrNull { it.value == raw?.trim() }
	}
}
