package com.toptier.custom.ticket.domain

/** How urgent a ticket is; new tickets are [MEDIUM] unless asked otherwise. */
enum class TicketPriority(val value: String) {
	LOW("low"),
	MEDIUM("medium"),
	HIGH("high"),
	CRITICAL("critical"),
	;

	companion object {
		/** The priority whose JSON [value] is [raw], else null. */
		fun fromValue(raw: String?): TicketPriority? = entries.firstOrNull { it.value == raw?.trim() }
	}
}
