package com.toptier.custom.ticket.service

import com.toptier.custom.ticket.domain.Ticket
import java.security.MessageDigest
import java.util.HexFormat

/** The `details.md` a ticket's workspace starts with, and the fingerprint used to tell whether it was changed since. */
object TicketDetailsTemplate {

	/** The file's [text] and its [sha256]. */
	data class Rendered(val text: String, val sha256: String)

	/** `details.md` for [ticket]: its title, a line of facts, its description, and empty findings and plan. */
	fun render(ticket: Ticket): Rendered {
		val text = listOf(
			"# #${ticket.number} ${ticket.title}",
			"",
			"Type: ${ticket.type.value} · Priority: ${ticket.priority.value} · Status: ${ticket.status.value}",
			"",
			"## Description",
			"",
			ticket.description ?: "No description.",
			"",
			"## Findings",
			"",
			"Not investigated yet.",
			"",
			"## Plan",
			"",
			"Not written yet.",
			"",
		).joinToString("\n")
		return Rendered(text, sha256(text))
	}

	/** The SHA-256 of [text] as UTF-8, in lowercase hex. */
	fun sha256(text: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}
