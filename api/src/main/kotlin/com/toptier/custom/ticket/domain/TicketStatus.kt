package com.toptier.custom.ticket.domain

/**
 * Where a ticket is in its life. [value] is the JSON and database form, [description] is shown to people.
 * A [bugOnly] status is never used by an improvement.
 */
enum class TicketStatus(val value: String, val description: String, val bugOnly: Boolean = false) {
	FILED("filed", "Reported, not yet looked at."),
	OPEN("open", "Accepted and ready to be worked on."),
	IN_PROGRESS("in_progress", "Someone is working on it."),
	IN_REVIEW("in_review", "The fix or change is waiting for review."),
	MERGED("merged", "The change is merged and not yet released."),
	DEPLOYED("deployed", "The change is released."),
	CLOSED("closed", "Finished or dropped; no more work planned."),
	REOPENED("reopened", "Bugs only. A deployed or closed bug that needs more work.", bugOnly = true),
	INCORRECT_EXPECTATION(
		"incorrect_expectation",
		"Bugs only. The behaviour is as designed; the report was a misunderstanding.",
		bugOnly = true,
	),
	OUT_OF_SCOPE("out_of_scope", "Will not be done as part of this project."),
	BACKLOG("backlog", "Valid, but parked for later."),
	;

	/** True when a ticket of [type] may have this status. */
	fun usableBy(type: TicketType): Boolean = !bugOnly || type == TicketType.BUG

	companion object {
		/** The status whose JSON [value] is [raw], else null. */
		fun fromValue(raw: String?): TicketStatus? = entries.firstOrNull { it.value == raw?.trim() }
	}
}
