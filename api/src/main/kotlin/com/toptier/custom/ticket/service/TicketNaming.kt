package com.toptier.custom.ticket.service

import com.toptier.custom.ticket.domain.TicketType

/** Names a ticket's workspace: a slug from its title, and the branch and folder built from its number and slug. */
object TicketNaming {

	private const val SLUG_MAX = 40
	private val NOT_SLUG = Regex("[^a-z0-9]+")

	/** The title lowercased, runs of anything but `a-z0-9` made one `-`, trimmed and cut to 40; `ticket` when nothing is left. */
	fun slug(title: String): String =
		title.lowercase().replace(NOT_SLUG, "-").trim('-').take(SLUG_MAX).trim('-').ifEmpty { "ticket" }

	/** `bugs/<number>-<slug>` for a bug, `updates/<number>-<slug>` for an improvement. */
	fun branch(type: TicketType, number: Int, slug: String): String = "${group(type)}/$number-$slug"

	/** `feature/bugs/<number>-<slug>` or `feature/updates/<number>-<slug>`. */
	fun folder(type: TicketType, number: Int, slug: String): String = "feature/${group(type)}/$number-$slug"

	private fun group(type: TicketType): String = if (type == TicketType.BUG) "bugs" else "updates"
}
