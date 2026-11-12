package com.toptier.custom.ticket

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
import com.toptier.custom.ticket.domain.TicketType.BUG
import com.toptier.custom.ticket.domain.TicketType.IMPROVEMENT
import com.toptier.custom.ticket.service.TicketStatusRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TicketStatusRulesTests {

	@Test
	fun `a bug follows the whole flow`() {
		assertEquals(listOf(OPEN, INCORRECT_EXPECTATION, OUT_OF_SCOPE, BACKLOG, CLOSED), TicketStatusRules.allowed(BUG, FILED))
		assertEquals(listOf(OPEN, CLOSED), TicketStatusRules.allowed(BUG, BACKLOG))
		assertEquals(listOf(IN_PROGRESS, CLOSED), TicketStatusRules.allowed(BUG, OPEN))
		assertEquals(listOf(IN_REVIEW, CLOSED), TicketStatusRules.allowed(BUG, IN_PROGRESS))
		assertEquals(listOf(MERGED, CLOSED), TicketStatusRules.allowed(BUG, IN_REVIEW))
		assertEquals(listOf(DEPLOYED, CLOSED), TicketStatusRules.allowed(BUG, MERGED))
		assertEquals(listOf(REOPENED, CLOSED), TicketStatusRules.allowed(BUG, DEPLOYED))
		assertEquals(listOf(REOPENED), TicketStatusRules.allowed(BUG, CLOSED))
		assertEquals(listOf(IN_PROGRESS, CLOSED), TicketStatusRules.allowed(BUG, REOPENED))
	}

	@Test
	fun `an improvement skips the bug-only statuses`() {
		assertEquals(listOf(OPEN, OUT_OF_SCOPE, BACKLOG, CLOSED), TicketStatusRules.allowed(IMPROVEMENT, FILED))
		assertEquals(listOf(CLOSED), TicketStatusRules.allowed(IMPROVEMENT, DEPLOYED))
		assertEquals(emptyList(), TicketStatusRules.allowed(IMPROVEMENT, CLOSED))
		assertFalse(TicketStatus.entries.any { REOPENED in TicketStatusRules.allowed(IMPROVEMENT, it) })
		assertFalse(TicketStatus.entries.any { INCORRECT_EXPECTATION in TicketStatusRules.allowed(IMPROVEMENT, it) })
	}

	@Test
	fun `out of scope and incorrect expectation can only be closed`() {
		for (type in listOf(BUG, IMPROVEMENT)) {
			assertEquals(listOf(CLOSED), TicketStatusRules.allowed(type, OUT_OF_SCOPE))
		}
		assertEquals(listOf(CLOSED), TicketStatusRules.allowed(BUG, INCORRECT_EXPECTATION))
	}

	@Test
	fun `every status except closed can be closed`() {
		for (type in listOf(BUG, IMPROVEMENT)) {
			for (status in TicketStatus.entries.filter { it != CLOSED && it.usableBy(type) }) {
				assertTrue(CLOSED in TicketStatusRules.allowed(type, status), "$type $status")
			}
		}
	}

	@Test
	fun `nothing moves straight to a later status`() {
		assertFalse(IN_PROGRESS in TicketStatusRules.allowed(BUG, FILED))
		assertFalse(MERGED in TicketStatusRules.allowed(BUG, FILED))
		assertFalse(REOPENED in TicketStatusRules.allowed(BUG, MERGED))
		assertFalse(REOPENED in TicketStatusRules.allowed(BUG, IN_REVIEW))
	}

	@Test
	fun `a type can have the statuses it can use`() {
		assertEquals(11, TicketStatusRules.usableBy(BUG).size)
		assertEquals(9, TicketStatusRules.usableBy(IMPROVEMENT).size)
	}
}
