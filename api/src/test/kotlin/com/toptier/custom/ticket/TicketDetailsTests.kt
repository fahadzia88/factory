package com.toptier.custom.ticket

import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.support.MutableClock
import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketEvent
import com.toptier.custom.ticket.domain.TicketEventRepository
import com.toptier.custom.ticket.domain.TicketPriority
import com.toptier.custom.ticket.domain.TicketRepository
import com.toptier.custom.ticket.domain.TicketStatus
import com.toptier.custom.ticket.domain.TicketType
import com.toptier.custom.ticket.exception.AlreadyApprovedException
import com.toptier.custom.ticket.exception.NoFindingsException
import com.toptier.custom.ticket.exception.TicketNotFoundException
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.WorkspaceMissingException
import com.toptier.custom.ticket.service.TicketDetails
import com.toptier.custom.ticket.service.TicketDetailsTemplate
import com.toptier.custom.ticket.service.TicketHistory
import com.toptier.custom.ticket.service.TicketWorktree
import com.toptier.custom.ticket.service.TicketWorktrees
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@Import(TicketDetails::class, TicketHistory::class, TicketTestConfig::class)
class TicketDetailsTests {

	@Autowired
	lateinit var details: TicketDetails

	@Autowired
	lateinit var tickets: TicketRepository

	@Autowired
	lateinit var events: TicketEventRepository

	@Autowired
	lateinit var clock: MutableClock

	@MockitoBean
	lateinit var projects: ProjectService

	@MockitoBean
	lateinit var worktrees: TicketWorktrees

	@TempDir
	lateinit var worktree: Path

	lateinit var ticket: Ticket

	private val folder = "feature/bugs/1-crash"

	private val file: Path get() = worktree.resolve("$folder/details.md")

	@BeforeEach
	fun setUp() {
		clock.now = TicketTestConfig.START
		given(projects.get(1L)).willReturn(Project().apply { id = 1 })
		given(projects.get(3L)).willReturn(Project().apply { id = 3; archived = true })
		given(worktrees.find(1L, "bugs/1-crash")).willReturn(TicketWorktree(5, worktree.toString(), false))
		given(worktrees.find(3L, "bugs/1-crash")).willReturn(TicketWorktree(5, worktree.toString(), false))
		ticket = tickets.saveAndFlush(newTicket(projectId = 1))
		Files.createDirectories(file.parent)
		Files.writeString(file, TEMPLATE.text)
	}

	@Test
	fun `the template alone has no findings, and the file is served as is`() {
		val view = details.get(1, ticket.id!!)

		assertEquals("$folder/details.md", view.path)
		assertEquals(TEMPLATE.text, view.content)
		assertFalse(view.hasFindings)
		assertFalse(details.hasFindings(ticket))
	}

	@Test
	fun `a changed file has findings, including uncommitted edits`() {
		Files.writeString(file, TEMPLATE.text.replace("Not investigated yet.", "The cookie is dropped."))

		val view = details.get(1, ticket.id!!)

		assertTrue(view.hasFindings)
		assertTrue(view.content.contains("The cookie is dropped."))
		assertTrue(details.hasFindings(ticket))
	}

	@Test
	fun `a file over 200 KB has findings but can't be served`() {
		Files.writeString(file, TEMPLATE.text + "x".repeat(201 * 1024))

		assertTrue(details.hasFindings(ticket))
		assertThrows<WorkspaceMissingException> { details.get(1, ticket.id!!) }
	}

	@Test
	fun `without a workspace, a worktree or the file there is nothing to read and no findings`() {
		val never = tickets.saveAndFlush(newTicket(projectId = 1, number = 2).apply {
			workspaceState = "none"
			branch = null
			folder = null
		})
		assertFalse(details.hasFindings(never))
		assertThrows<WorkspaceMissingException> { details.get(1, never.id!!) }

		given(worktrees.find(1L, "bugs/1-crash")).willReturn(TicketWorktree(5, worktree.toString(), true))
		assertFalse(details.hasFindings(ticket))
		assertThrows<WorkspaceMissingException> { details.get(1, ticket.id!!) }

		given(worktrees.find(1L, "bugs/1-crash")).willReturn(null)
		assertThrows<WorkspaceMissingException> { details.get(1, ticket.id!!) }

		given(worktrees.find(1L, "bugs/1-crash")).willReturn(TicketWorktree(5, worktree.toString(), false))
		Files.delete(file)
		assertFalse(details.hasFindings(ticket))
		assertThrows<WorkspaceMissingException> { details.get(1, ticket.id!!) }
	}

	@Test
	fun `a details file that links outside the worktree is not read`() {
		val outside = Files.createTempFile("outside", ".md")
		Files.writeString(outside, "secret")
		Files.delete(file)
		Files.createSymbolicLink(file, outside)

		assertFalse(details.hasFindings(ticket))
		assertThrows<WorkspaceMissingException> { details.get(1, ticket.id!!) }
	}

	@Test
	fun `approval needs findings, then switches to implementer mode once`() {
		assertThrows<NoFindingsException> { details.approve(1, ticket.id!!) }
		assertEquals("investigator", tickets.findById(ticket.id!!).get().mode)
		assertEquals(0, events.count())

		Files.writeString(file, TEMPLATE.text.replace("Not written yet.", "Fix the cookie."))
		clock.advance(java.time.Duration.ofMinutes(3))
		val approved = details.approve(1, ticket.id!!)

		assertEquals("implementer", approved.mode)
		assertEquals(clock.now, approved.approvedAt)
		assertEquals(clock.now, approved.updatedAt)
		val entries = events.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.id!!)
		assertEquals(listOf(TicketEvent.KIND_APPROVE), entries.map { it.kind })
		assertNotNull(entries.single().detail)
		assertThrows<AlreadyApprovedException> { details.approve(1, ticket.id!!) }
		assertEquals(1, events.count())
	}

	@Test
	fun `approval needs a workspace and an active project`() {
		val never = tickets.saveAndFlush(newTicket(projectId = 1, number = 2).apply {
			workspaceState = "none"
			branch = null
			folder = null
		})
		assertThrows<WorkspaceMissingException> { details.approve(1, never.id!!) }
		assertNull(tickets.findById(never.id!!).get().approvedAt)

		val archived = tickets.saveAndFlush(newTicket(projectId = 3))
		assertThrows<TicketProjectArchivedException> { details.approve(3, archived.id!!) }
		assertEquals(0, events.count())
	}

	@Test
	fun `other projects' and unknown tickets are not found`() {
		given(projects.get(2L)).willReturn(Project().apply { id = 2 })

		assertThrows<TicketNotFoundException> { details.get(2, ticket.id!!) }
		assertThrows<TicketNotFoundException> { details.approve(1, ticket.id!! + 100) }
	}

	private fun newTicket(projectId: Long, number: Int = 1) = Ticket().apply {
		this.projectId = projectId
		this.number = number
		type = TicketType.BUG
		title = "Crash"
		priority = TicketPriority.MEDIUM
		status = TicketStatus.IN_PROGRESS
		branch = "bugs/1-crash"
		this.folder = this@TicketDetailsTests.folder
		slug = "crash"
		workspaceState = "ready"
		detailsBaseline = TEMPLATE.sha256
		createdAt = TicketTestConfig.START
		updatedAt = TicketTestConfig.START
	}

	private companion object {
		val TEMPLATE = TicketDetailsTemplate.render(Ticket().apply {
			number = 1
			type = TicketType.BUG
			title = "Crash"
			priority = TicketPriority.MEDIUM
			status = TicketStatus.IN_PROGRESS
		})
	}
}
