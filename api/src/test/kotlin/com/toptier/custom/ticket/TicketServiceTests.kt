package com.toptier.custom.ticket

import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.support.MutableClock
import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketEvent
import com.toptier.custom.ticket.domain.TicketEventRepository
import com.toptier.custom.ticket.domain.TicketPriority
import com.toptier.custom.ticket.domain.TicketRepository
import com.toptier.custom.ticket.domain.TicketStatus
import com.toptier.custom.ticket.domain.TicketType
import com.toptier.custom.ticket.dto.CreateTicketRequest
import com.toptier.custom.ticket.exception.InvalidTicketException
import com.toptier.custom.ticket.exception.InvalidTicketRequestException
import com.toptier.custom.ticket.exception.InvalidTransitionException
import com.toptier.custom.ticket.exception.TicketNotFoundException
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.TicketTypeFixedException
import com.toptier.custom.ticket.service.TicketHistory
import com.toptier.custom.ticket.service.TicketService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@Import(TicketService::class, TicketHistory::class, TicketTestConfig::class)
class TicketServiceTests {

	@Autowired
	lateinit var service: TicketService

	@Autowired
	lateinit var tickets: TicketRepository

	@Autowired
	lateinit var events: TicketEventRepository

	@Autowired
	lateinit var clock: MutableClock

	@MockitoBean
	lateinit var projects: ProjectService

	@BeforeEach
	fun projects() {
		clock.now = TicketTestConfig.START
		given(projects.get(1L)).willReturn(project(1, archived = false))
		given(projects.get(2L)).willReturn(project(2, archived = false))
		given(projects.get(3L)).willReturn(project(3, archived = true))
		given(projects.get(9L)).willThrow(ProjectNotFoundException(9))
	}

	@Test
	fun `a new ticket is filed with the next number, a medium priority and a creation entry`() {
		val first = service.create(1, CreateTicketRequest(type = "bug", title = " Login fails ", description = "Safari only"))
		val second = service.create(1, CreateTicketRequest(type = "improvement", title = "Dark mode", priority = "low"))
		val other = service.create(2, CreateTicketRequest(type = "bug", title = "Elsewhere"))

		assertEquals(listOf(1, 2, 1), listOf(first.number, second.number, other.number))
		assertEquals(TicketType.BUG, first.type)
		assertEquals("Login fails", first.title)
		assertEquals(TicketStatus.FILED, first.status)
		assertEquals(TicketPriority.MEDIUM, first.priority)
		assertEquals(TicketPriority.LOW, second.priority)
		val history = service.history(1, first.id!!)
		assertEquals(listOf("created"), history.map { it.kind })
		assertEquals(clock.now, history.single().createdAt)
		val initial = history.single().newValue!!
		assertContains(initial, "\"title\":\"Login fails\"")
		assertContains(initial, "\"type\":\"bug\"")
		assertContains(initial, "\"priority\":\"medium\"")
		assertContains(initial, "\"status\":\"filed\"")
	}

	@Test
	fun `a ticket without a title or a known type is refused and nothing is written`() {
		val bad = listOf(
			CreateTicketRequest(type = "bug", title = "  "),
			CreateTicketRequest(type = "bug", title = null),
			CreateTicketRequest(type = "bug", title = "x".repeat(201)),
			CreateTicketRequest(type = null, title = "Fine"),
			CreateTicketRequest(type = "task", title = "Fine"),
			CreateTicketRequest(type = "bug", title = "Fine", priority = "urgent"),
			CreateTicketRequest(type = "bug", title = "Fine", description = "d".repeat(10_001)),
		)
		for (request in bad) assertThrows<InvalidTicketException> { service.create(1, request) }

		assertEquals(0, tickets.count())
		assertEquals(0, events.count())
	}

	@Test
	fun `a blank description is stored as none`() {
		val ticket = service.create(1, CreateTicketRequest(type = "bug", title = "T", description = "   "))

		assertNull(ticket.description)
	}

	@Test
	fun `tickets list newest first and filter by type and status`() {
		val bug = service.create(1, CreateTicketRequest(type = "bug", title = "Bug"))
		val improvement = service.create(1, CreateTicketRequest(type = "improvement", title = "Improvement"))
		tickets.saveAndFlush(bug.apply { status = TicketStatus.OPEN })

		assertEquals(listOf(improvement.id, bug.id), service.list(1, null, null).map { it.id })
		assertEquals(listOf(bug.id), service.list(1, "bug", null).map { it.id })
		assertEquals(listOf(bug.id), service.list(1, null, "open").map { it.id })
		assertEquals(emptyList(), service.list(1, "improvement", "open"))
		assertEquals(emptyList(), service.list(2, null, null))
		assertThrows<InvalidTicketRequestException> { service.list(1, "task", null) }
		assertThrows<InvalidTicketRequestException> { service.list(1, null, "done") }
	}

	@Test
	fun `every edited field writes one entry with its old and new value`() {
		val ticket = service.create(1, CreateTicketRequest(type = "bug", title = "Old title", description = "Old"))
		clock.advance(Duration.ofMinutes(5))

		val edited = service.edit(
			1,
			ticket.id!!,
			mapOf("title" to " New title ", "description" to null, "priority" to "high", "pullRequestUrl" to "https://github.com/o/r/pull/3"),
		)

		assertEquals("New title", edited.title)
		assertNull(edited.description)
		assertEquals(TicketPriority.HIGH, edited.priority)
		assertEquals("https://github.com/o/r/pull/3", edited.pullRequestUrl)
		assertEquals(clock.now, edited.updatedAt)
		val edits = service.history(1, ticket.id!!).filter { it.kind == TicketEvent.KIND_EDIT }
		assertEquals(listOf("title", "description", "priority", "pullRequestUrl"), edits.map { it.field })
		assertEquals("Old title", edits[0].oldValue)
		assertEquals("New title", edits[0].newValue)
		assertEquals("Old", edits[1].oldValue)
		assertNull(edits[1].newValue)
		assertEquals("medium", edits[2].oldValue)
		assertEquals("high", edits[2].newValue)
		assertNull(edits[3].oldValue)
	}

	@Test
	fun `an edit that changes nothing writes no entry`() {
		val ticket = service.create(1, CreateTicketRequest(type = "bug", title = "Same", priority = "high"))
		clock.advance(Duration.ofMinutes(5))

		val edited = service.edit(1, ticket.id!!, mapOf("title" to "Same", "priority" to "high", "pullRequestUrl" to " "))

		assertEquals(ticket.updatedAt, edited.updatedAt)
		assertEquals(listOf("created"), service.history(1, ticket.id!!).map { it.kind })
	}

	@Test
	fun `an invalid edit is refused as a whole and writes nothing`() {
		val ticket = service.create(1, CreateTicketRequest(type = "bug", title = "Keep"))
		val bad = listOf(
			mapOf("title" to "Changed", "pullRequestUrl" to "ftp://example.com/x"),
			mapOf("title" to "Changed", "pullRequestUrl" to "not a link"),
			mapOf("title" to "Changed", "pullRequestUrl" to "https:///nohost"),
			mapOf("title" to ""),
			mapOf("title" to null),
			mapOf("title" to 5),
			mapOf("priority" to "urgent"),
			mapOf("status" to "open"),
			mapOf("number" to 9),
		)
		for (changes in bad) assertThrows<InvalidTicketException> { service.edit(1, ticket.id!!, changes) }

		assertEquals("Keep", tickets.findById(ticket.id!!).get().title)
		assertEquals(listOf("created"), service.history(1, ticket.id!!).map { it.kind })
	}

	@Test
	fun `a ticket's type can't be changed`() {
		val ticket = service.create(1, CreateTicketRequest(type = "bug", title = "T"))

		assertThrows<TicketTypeFixedException> { service.edit(1, ticket.id!!, mapOf("type" to "improvement")) }

		assertEquals(TicketType.BUG, tickets.findById(ticket.id!!).get().type)
		assertEquals(listOf("created"), service.history(1, ticket.id!!).map { it.kind })
	}

	@Test
	fun `an archived project's tickets can be read but not created or edited`() {
		val ticket = tickets.saveAndFlush(Ticket().apply {
			projectId = 3
			number = 1
			title = "Old"
			createdAt = clock.now
			updatedAt = clock.now
		})

		assertThrows<TicketProjectArchivedException> { service.create(3, CreateTicketRequest(type = "bug", title = "New")) }
		assertThrows<TicketProjectArchivedException> { service.edit(3, ticket.id!!, mapOf("title" to "Changed")) }

		assertEquals("Old", service.get(3, ticket.id!!).title)
		assertEquals(1, service.list(3, null, null).size)
		assertTrue(service.history(3, ticket.id!!).isEmpty())
		assertEquals(1, tickets.count())
	}

	@Test
	fun `unknown tickets, other projects' tickets and unknown projects are not found`() {
		val ticket = service.create(1, CreateTicketRequest(type = "bug", title = "Mine"))

		assertThrows<TicketNotFoundException> { service.get(1, ticket.id!! + 100) }
		assertThrows<TicketNotFoundException> { service.get(2, ticket.id!!) }
		assertThrows<TicketNotFoundException> { service.history(2, ticket.id!!) }
		assertThrows<TicketNotFoundException> { service.edit(2, ticket.id!!, mapOf("title" to "x")) }
		assertThrows<ProjectNotFoundException> { service.get(9, ticket.id!!) }
		assertThrows<ProjectNotFoundException> { service.list(9, null, null) }
		assertThrows<ProjectNotFoundException> { service.create(9, CreateTicketRequest(type = "bug", title = "x")) }
	}

	@Test
	fun `a ticket moves through the whole flow, one entry per step`() {
		val ticket = service.create(1, CreateTicketRequest(type = "bug", title = "T"))
		val flow = listOf("open", "in_progress", "in_review", "merged", "deployed", "closed")

		for (next in flow) {
			clock.advance(Duration.ofMinutes(1))
			val moved = service.changeStatus(1, ticket.id!!, next)
			assertEquals(next, moved.status.value)
			assertEquals(clock.now, moved.updatedAt)
		}

		val entries = service.history(1, ticket.id!!).filter { it.kind == TicketEvent.KIND_STATUS }
		assertEquals(flow, entries.map { it.newValue })
		assertEquals(listOf("filed") + flow.dropLast(1), entries.map { it.oldValue })
		assertTrue(entries.all { it.field == "status" })
	}

	@Test
	fun `a refused move changes nothing and writes no entry`() {
		val ticket = service.create(1, CreateTicketRequest(type = "bug", title = "T"))

		val skip = assertThrows<InvalidTransitionException> { service.changeStatus(1, ticket.id!!, "merged") }
		assertThrows<InvalidTransitionException> { service.changeStatus(1, ticket.id!!, "in_progress") }
		assertThrows<InvalidTransitionException> { service.changeStatus(1, ticket.id!!, "reopened") }
		assertThrows<InvalidTransitionException> { service.changeStatus(1, ticket.id!!, "filed") }
		assertThrows<InvalidTicketException> { service.changeStatus(1, ticket.id!!, "done") }
		assertThrows<InvalidTicketException> { service.changeStatus(1, ticket.id!!, null) }

		assertEquals(TicketStatus.FILED, skip.from)
		assertEquals(TicketStatus.MERGED, skip.to)
		assertEquals(listOf(TicketStatus.OPEN, TicketStatus.INCORRECT_EXPECTATION, TicketStatus.OUT_OF_SCOPE, TicketStatus.BACKLOG, TicketStatus.CLOSED), skip.allowed)
		assertEquals(TicketStatus.FILED, tickets.findById(ticket.id!!).get().status)
		assertEquals(listOf("created"), service.history(1, ticket.id!!).map { it.kind })
	}

	@Test
	fun `a bug can be reopened from deployed and closed, then goes back to in progress`() {
		val bug = ticketIn(TicketType.BUG, TicketStatus.DEPLOYED)
		assertEquals(TicketStatus.REOPENED, service.changeStatus(1, bug.id!!, "reopened").status)
		assertThrows<InvalidTransitionException> { service.changeStatus(1, bug.id!!, "open") }
		assertEquals(TicketStatus.IN_PROGRESS, service.changeStatus(1, bug.id!!, "in_progress").status)

		val closed = ticketIn(TicketType.BUG, TicketStatus.CLOSED)
		assertEquals(TicketStatus.REOPENED, service.changeStatus(1, closed.id!!, "reopened").status)
		assertEquals(TicketStatus.CLOSED, service.changeStatus(1, closed.id!!, "closed").status)
	}

	@Test
	fun `a bug can't be reopened before it is merged`() {
		for (status in listOf(TicketStatus.FILED, TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.IN_REVIEW, TicketStatus.MERGED)) {
			val bug = ticketIn(TicketType.BUG, status)

			assertThrows<InvalidTransitionException> { service.changeStatus(1, bug.id!!, "reopened") }
			assertEquals(status, tickets.findById(bug.id!!).get().status)
		}
	}

	@Test
	fun `an improvement can't be reopened or called an incorrect expectation, and closed is final`() {
		val deployed = ticketIn(TicketType.IMPROVEMENT, TicketStatus.DEPLOYED)
		val filed = ticketIn(TicketType.IMPROVEMENT, TicketStatus.FILED)
		assertThrows<InvalidTransitionException> { service.changeStatus(1, deployed.id!!, "reopened") }
		assertThrows<InvalidTransitionException> { service.changeStatus(1, filed.id!!, "incorrect_expectation") }

		assertEquals(TicketStatus.CLOSED, service.changeStatus(1, deployed.id!!, "closed").status)
		assertThrows<InvalidTransitionException> { service.changeStatus(1, deployed.id!!, "reopened") }
		assertThrows<InvalidTransitionException> { service.changeStatus(1, deployed.id!!, "in_progress") }
	}

	@Test
	fun `filed tickets can be parked, dropped or declined, and a parked one reopened for work`() {
		val parked = ticketIn(TicketType.BUG, TicketStatus.FILED)
		assertEquals(TicketStatus.BACKLOG, service.changeStatus(1, parked.id!!, "backlog").status)
		assertEquals(TicketStatus.OPEN, service.changeStatus(1, parked.id!!, "open").status)

		val scope = ticketIn(TicketType.IMPROVEMENT, TicketStatus.FILED)
		assertEquals(TicketStatus.OUT_OF_SCOPE, service.changeStatus(1, scope.id!!, "out_of_scope").status)
		assertThrows<InvalidTransitionException> { service.changeStatus(1, scope.id!!, "open") }

		val declined = ticketIn(TicketType.BUG, TicketStatus.FILED)
		assertEquals(TicketStatus.INCORRECT_EXPECTATION, service.changeStatus(1, declined.id!!, "incorrect_expectation").status)
		assertEquals(TicketStatus.CLOSED, service.changeStatus(1, declined.id!!, "closed").status)
	}

	@Test
	fun `an archived project refuses a status change but its tickets can be read`() {
		val ticket = tickets.saveAndFlush(Ticket().apply {
			projectId = 3
			number = 1
			title = "Old"
			createdAt = clock.now
			updatedAt = clock.now
		})

		assertThrows<TicketProjectArchivedException> { service.changeStatus(3, ticket.id!!, "open") }

		assertEquals(TicketStatus.FILED, service.get(3, ticket.id!!).status)
		assertTrue(service.archived(3))
		assertFalse(service.archived(1))
		assertEquals(0, events.count())
	}

	@Test
	fun `status changes of unknown and other projects' tickets are not found`() {
		val ticket = service.create(1, CreateTicketRequest(type = "bug", title = "Mine"))

		assertThrows<TicketNotFoundException> { service.changeStatus(2, ticket.id!!, "open") }
		assertThrows<TicketNotFoundException> { service.changeStatus(1, ticket.id!! + 100, "open") }
		assertThrows<ProjectNotFoundException> { service.changeStatus(9, ticket.id!!, "open") }
	}

	@Test
	fun `statuses lists all eleven`() {
		assertEquals(11, service.statuses(1).size)
		assertThrows<ProjectNotFoundException> { service.statuses(9) }
	}

	private fun ticketIn(type: TicketType, status: TicketStatus): Ticket = tickets.saveAndFlush(Ticket().apply {
		projectId = 1
		number = (tickets.maxNumber(1) ?: 0) + 1
		this.type = type
		this.status = status
		title = "T"
		createdAt = clock.now
		updatedAt = clock.now
	})

	private fun project(id: Long, archived: Boolean) = Project().apply {
		this.id = id
		this.archived = archived
	}
}
