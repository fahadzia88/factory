package com.toptier.custom.ticket

import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketEvent
import com.toptier.custom.ticket.domain.TicketEventRepository
import com.toptier.custom.ticket.domain.TicketPriority
import com.toptier.custom.ticket.domain.TicketRepository
import com.toptier.custom.ticket.domain.TicketStatus
import com.toptier.custom.ticket.domain.TicketType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DataJpaTest
class TicketRepositoryTests {

	@Autowired
	lateinit var tickets: TicketRepository

	@Autowired
	lateinit var events: TicketEventRepository

	@Test
	fun `a number is used once per project`() {
		tickets.saveAndFlush(ticket(1, 1))
		tickets.saveAndFlush(ticket(2, 1))

		assertThrows<DataIntegrityViolationException> { tickets.saveAndFlush(ticket(1, 1)) }
	}

	@Test
	fun `tickets list newest number first, with the project's highest number`() {
		tickets.saveAndFlush(ticket(1, 2))
		tickets.saveAndFlush(ticket(1, 5))
		tickets.saveAndFlush(ticket(2, 9))

		assertEquals(listOf(5, 2), tickets.findByProjectIdOrderByNumberDesc(1).map { it.number })
		assertEquals(5, tickets.maxNumber(1))
		assertNull(tickets.maxNumber(3))
	}

	@Test
	fun `a ticket is found only in its own project`() {
		val id = tickets.saveAndFlush(ticket(1, 1)).id!!

		assertEquals(id, tickets.findByIdAndProjectId(id, 1)?.id)
		assertNull(tickets.findByIdAndProjectId(id, 2))
	}

	@Test
	fun `type, priority and status are stored and read back`() {
		val saved = tickets.saveAndFlush(ticket(1, 1).apply {
			type = TicketType.IMPROVEMENT
			priority = TicketPriority.CRITICAL
			status = TicketStatus.IN_PROGRESS
			description = "d".repeat(10_000)
		})

		val read = tickets.findById(saved.id!!).get()
		assertEquals(TicketType.IMPROVEMENT, read.type)
		assertEquals(TicketPriority.CRITICAL, read.priority)
		assertEquals(TicketStatus.IN_PROGRESS, read.status)
		assertEquals(10_000, read.description!!.length)
		assertEquals("none", read.workspaceState)
		assertEquals("investigator", read.mode)
	}

	@Test
	fun `events list oldest first and keep long values`() {
		val ticketId = tickets.saveAndFlush(ticket(1, 1)).id!!
		events.saveAndFlush(event(ticketId, "edit", Instant.parse("2026-11-06T10:01:00Z")).apply { newValue = "y".repeat(10_000) })
		events.saveAndFlush(event(ticketId, "created", Instant.parse("2026-11-06T10:00:00Z")))
		events.saveAndFlush(event(ticketId + 1, "created", Instant.parse("2026-11-06T09:00:00Z")))

		val listed = events.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId)
		assertEquals(listOf("created", "edit"), listed.map { it.kind })
		assertEquals(10_000, listed[1].newValue!!.length)
	}

	private fun ticket(projectId: Long, number: Int) = Ticket().apply {
		this.projectId = projectId
		this.number = number
		title = "Ticket $number"
		createdAt = NOW
		updatedAt = NOW
	}

	private fun event(ticketId: Long, kind: String, at: Instant) = TicketEvent().apply {
		this.ticketId = ticketId
		this.kind = kind
		createdAt = at
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-11-06T10:00:00Z")
	}
}
