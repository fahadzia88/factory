package com.toptier.custom.ticket.service

import com.toptier.custom.ticket.domain.TicketStatus
import com.toptier.custom.ticket.domain.TicketStatus.BACKLOG
import com.toptier.custom.ticket.domain.TicketStatus.CLOSED
import com.toptier.custom.ticket.domain.TicketStatus.DEPLOYED
import com.toptier.custom.ticket.domain.TicketStatus.FILED
import com.toptier.custom.ticket.domain.TicketStatus.INCORRECT_EXPECTATION
import com.toptier.custom.ticket.domain.TicketStatus.IN_PROGRESS
import com.toptier.custom.ticket.domain.TicketStatus.IN_REVIEW
import com.toptier.custom.ticket.domain.TicketStatus.MERGED
import com.toptier.custom.ticket.domain.TicketStatus.OPEN
import com.toptier.custom.ticket.domain.TicketStatus.OUT_OF_SCOPE
import com.toptier.custom.ticket.domain.TicketStatus.REOPENED
import com.toptier.custom.ticket.domain.TicketType

/**
 * The moves a ticket may make, one step at a time:
 * `filed` → `open` → `in_progress` → `in_review` → `merged` → `deployed` → `closed`;
 * `filed` → `incorrect_expectation` (bugs), `out_of_scope` or `backlog`; `backlog` → `open`;
 * `deployed` or `closed` → `reopened` (bugs) → `in_progress`; and any status but `closed` → `closed`.
 * Statuses a ticket's type can't use are left out, so a closed improvement has nowhere to go.
 */
object TicketStatusRules {

	private val STEPS: Map<TicketStatus, List<TicketStatus>> = mapOf(
		FILED to listOf(OPEN, INCORRECT_EXPECTATION, OUT_OF_SCOPE, BACKLOG),
		BACKLOG to listOf(OPEN),
		OPEN to listOf(IN_PROGRESS),
		IN_PROGRESS to listOf(IN_REVIEW),
		IN_REVIEW to listOf(MERGED),
		MERGED to listOf(DEPLOYED),
		DEPLOYED to listOf(REOPENED),
		CLOSED to listOf(REOPENED),
		REOPENED to listOf(IN_PROGRESS),
	)

	/** The statuses a ticket of [type] in status [from] can move to, in the order of the flow. */
	fun allowed(type: TicketType, from: TicketStatus): List<TicketStatus> {
		val steps = STEPS[from].orEmpty().filter { it.usableBy(type) }
		return if (from == CLOSED) steps else steps + CLOSED
	}

	/** Every status a ticket of [type] can have. */
	fun usableBy(type: TicketType): List<TicketStatus> = TicketStatus.entries.filter { it.usableBy(type) }
}
