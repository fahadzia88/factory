package com.toptier.custom.ticket

import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.security.config.SecurityConfig
import com.toptier.custom.security.config.SecurityProperties
import com.toptier.custom.ticket.controller.TicketController
import com.toptier.custom.ticket.controller.TicketExceptionHandler
import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketEvent
import com.toptier.custom.ticket.domain.TicketPriority
import com.toptier.custom.ticket.domain.TicketStatus
import com.toptier.custom.ticket.domain.TicketType
import com.toptier.custom.ticket.dto.CreateTicketRequest
import com.toptier.custom.ticket.exception.InvalidTicketException
import com.toptier.custom.ticket.exception.InvalidTicketRequestException
import com.toptier.custom.ticket.exception.InvalidTransitionException
import com.toptier.custom.ticket.exception.TicketNotFoundException
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.TicketTypeFixedException
import com.toptier.custom.ticket.exception.WorkspaceNotNeededException
import com.toptier.custom.ticket.service.TicketService
import com.toptier.custom.ticket.service.TicketWorktree
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Instant

@WebMvcTest(TicketController::class)
@Import(SecurityConfig::class, TicketExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class TicketControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@MockitoBean
	lateinit var tickets: TicketService

	@Test
	fun `rejects requests without a token`() {
		mockMvc.get(BASE).andExpect { status { isUnauthorized() } }
		mockMvc.post(BASE) { json("""{"type":"bug","title":"x"}""") }.andExpect { status { isUnauthorized() } }
		mockMvc.get("$BASE/21").andExpect { status { isUnauthorized() } }
		mockMvc.patch("$BASE/21") { json("""{"title":"x"}""") }.andExpect { status { isUnauthorized() } }
		mockMvc.get("$BASE/21/history").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `list answers tickets with their resting workspace values`() {
		given(tickets.list(12, null, null)).willReturn(listOf(ticket()))
		given(tickets.list(12, "bug", "open")).willReturn(emptyList())

		mockMvc.get(BASE) { auth() }.andExpect {
			status { isOk() }
			jsonPath("$[0].id") { value(21) }
			jsonPath("$[0].projectId") { value(12) }
			jsonPath("$[0].number") { value(3) }
			jsonPath("$[0].type") { value("bug") }
			jsonPath("$[0].title") { value("Login fails") }
			jsonPath("$[0].priority") { value("high") }
			jsonPath("$[0].status") { value("in_progress") }
			jsonPath("$[0].statusDescription") { value("Someone is working on it.") }
			jsonPath("$[0].allowedStatuses[0]") { value("in_review") }
			jsonPath("$[0].allowedStatuses[1]") { value("closed") }
			jsonPath("$[0].workspace.state") { value("none") }
			jsonPath("$[0].workspace.branch") { value(null as Any?) }
			jsonPath("$[0].workspace.worktree") { value(null as Any?) }
			jsonPath("$[0].mode") { value("investigator") }
			jsonPath("$[0].approvedAt") { value(null as Any?) }
			jsonPath("$[0].hasFindings") { value(false) }
			jsonPath("$[0].createdAt") { value("2026-11-06T10:00:00Z") }
		}
		mockMvc.get("$BASE?type=bug&status=open") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$") { isEmpty() }
		}
	}

	@Test
	fun `an unknown filter is a bad request`() {
		given(tickets.list(12, "task", null)).willThrow(InvalidTicketRequestException("\"task\" isn't a ticket type."))

		mockMvc.get("$BASE?type=task") { auth() }.andExpect { problem(400, "invalid_request", "\"task\" isn't a ticket type.") }
	}

	@Test
	fun `create answers 201 with the ticket`() {
		given(tickets.create(12, CreateTicketRequest(type = "bug", title = "Login fails", priority = "high"))).willReturn(ticket())

		mockMvc.post(BASE) { auth(); json("""{"type":"bug","title":"Login fails","priority":"high"}""") }.andExpect {
			status { isCreated() }
			jsonPath("$.id") { value(21) }
			jsonPath("$.status") { value("in_progress") }
		}
	}

	@Test
	fun `create refusals map to 400, 404 and 409`() {
		given(tickets.create(12, CreateTicketRequest(type = "bug", title = ""))).willThrow(InvalidTicketException("Enter a title."))
		given(tickets.create(12, CreateTicketRequest(type = "task", title = "x"))).willThrow(InvalidTicketException("Choose a type: bug or improvement."))
		given(tickets.create(13, CreateTicketRequest(type = "bug", title = "x"))).willThrow(ProjectNotFoundException(13))
		given(tickets.create(14, CreateTicketRequest(type = "bug", title = "x"))).willThrow(TicketProjectArchivedException())

		mockMvc.post(BASE) { auth(); json("""{"type":"bug","title":""}""") }.andExpect { problem(400, "invalid_ticket", "Enter a title.") }
		mockMvc.post(BASE) { auth(); json("""{"type":"task","title":"x"}""") }.andExpect { problem(400, "invalid_ticket") }
		mockMvc.post("/api/projects/13/tickets") { auth(); json("""{"type":"bug","title":"x"}""") }
			.andExpect { problem(404, "project_not_found", "Project not found.") }
		mockMvc.post("/api/projects/14/tickets") { auth(); json("""{"type":"bug","title":"x"}""") }.andExpect { problem(409, "project_archived") }
		mockMvc.post(BASE) { auth(); json("{not json") }.andExpect { problem(400, "invalid_request") }
	}

	@Test
	fun `get answers the ticket, not found and invalid ids`() {
		given(tickets.get(12, 21)).willReturn(ticket())
		given(tickets.get(12, 22)).willThrow(TicketNotFoundException())

		mockMvc.get("$BASE/21") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.number") { value(3) }
			jsonPath("$.description") { value("Safari only") }
		}
		mockMvc.get("$BASE/22") { auth() }.andExpect { problem(404, "ticket_not_found", "Ticket not found.") }
		mockMvc.get("$BASE/abc") { auth() }.andExpect { problem(400, "invalid_request") }
	}

	@Test
	fun `edit passes the fields present, including nulls, and maps refusals`() {
		given(tickets.edit(12, 21, mapOf("title" to "New", "description" to null))).willReturn(ticket())
		given(tickets.edit(12, 21, mapOf("type" to "improvement"))).willThrow(TicketTypeFixedException())
		given(tickets.edit(12, 21, mapOf("pullRequestUrl" to "nope"))).willThrow(InvalidTicketException("Use a link starting with http:// or https://."))
		given(tickets.edit(12, 21, mapOf("title" to "x"))).willThrow(TicketProjectArchivedException())

		mockMvc.patch("$BASE/21") { auth(); json("""{"title":"New","description":null}""") }.andExpect { status { isOk() } }
		mockMvc.patch("$BASE/21") { auth(); json("""{"type":"improvement"}""") }.andExpect { problem(400, "ticket_type_fixed") }
		mockMvc.patch("$BASE/21") { auth(); json("""{"pullRequestUrl":"nope"}""") }.andExpect { problem(400, "invalid_ticket") }
		mockMvc.patch("$BASE/21") { auth(); json("""{"title":"x"}""") }.andExpect { problem(409, "project_archived") }
		mockMvc.patch("$BASE/21") { auth(); json("[1,2]") }.andExpect { problem(400, "invalid_request") }
	}

	@Test
	fun `history answers the events oldest first`() {
		given(tickets.history(12, 21)).willReturn(
			listOf(
				TicketEvent().apply {
					id = 1
					ticketId = 21
					kind = "created"
					newValue = """{"title":"Login fails"}"""
					createdAt = AT
				},
				TicketEvent().apply {
					id = 2
					ticketId = 21
					kind = "edit"
					field = "title"
					oldValue = "Login fails"
					newValue = "Login fails on Safari"
					createdAt = AT.plusSeconds(60)
				},
			),
		)

		mockMvc.get("$BASE/21/history") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$[0].kind") { value("created") }
			jsonPath("$[0].field") { value(null as Any?) }
			jsonPath("$[1].kind") { value("edit") }
			jsonPath("$[1].field") { value("title") }
			jsonPath("$[1].oldValue") { value("Login fails") }
			jsonPath("$[1].newValue") { value("Login fails on Safari") }
			jsonPath("$[1].createdAt") { value("2026-11-06T10:01:00Z") }
		}
	}

	@Test
	fun `an archived project's tickets have no allowed statuses`() {
		given(tickets.list(12, null, null)).willReturn(listOf(ticket()))
		given(tickets.archived(12)).willReturn(true)

		mockMvc.get(BASE) { auth() }.andExpect {
			status { isOk() }
			jsonPath("$[0].allowedStatuses") { isEmpty() }
		}
	}

	@Test
	fun `statuses answers every status with its description and types`() {
		given(tickets.statuses(12)).willReturn(TicketStatus.entries)
		given(tickets.statuses(13)).willThrow(ProjectNotFoundException(13))

		mockMvc.get("$BASE/statuses").andExpect { status { isUnauthorized() } }
		mockMvc.get("$BASE/statuses") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.length()") { value(11) }
			jsonPath("$[0].status") { value("filed") }
			jsonPath("$[0].description") { value("Reported, not yet looked at.") }
			jsonPath("$[0].types[0]") { value("bug") }
			jsonPath("$[0].types[1]") { value("improvement") }
			jsonPath("$[7].status") { value("reopened") }
			jsonPath("$[7].types.length()") { value(1) }
			jsonPath("$[7].types[0]") { value("bug") }
		}
		mockMvc.get("/api/projects/13/tickets/statuses") { auth() }.andExpect { problem(404, "project_not_found") }
	}

	@Test
	fun `a status change answers the ticket, and a refused one the transition`() {
		given(tickets.changeStatus(12, 21, "in_review")).willReturn(ticket().apply { status = TicketStatus.IN_REVIEW })
		given(tickets.changeStatus(12, 21, "merged")).willThrow(
			InvalidTransitionException(TicketStatus.IN_PROGRESS, TicketStatus.MERGED, listOf(TicketStatus.IN_REVIEW, TicketStatus.CLOSED)),
		)
		given(tickets.changeStatus(12, 21, "done")).willThrow(InvalidTicketException("\"done\" isn't a ticket status."))
		given(tickets.changeStatus(12, 22, "open")).willThrow(TicketNotFoundException())
		given(tickets.changeStatus(14, 21, "open")).willThrow(TicketProjectArchivedException())

		mockMvc.post("$BASE/21/status") { json("""{"status":"in_review"}""") }.andExpect { status { isUnauthorized() } }
		mockMvc.post("$BASE/21/status") { auth(); json("""{"status":"in_review"}""") }.andExpect {
			status { isOk() }
			jsonPath("$.status") { value("in_review") }
			jsonPath("$.allowedStatuses[0]") { value("merged") }
		}
		mockMvc.post("$BASE/21/status") { auth(); json("""{"status":"merged"}""") }.andExpect {
			problem(409, "invalid_transition")
			jsonPath("$.from") { value("in_progress") }
			jsonPath("$.to") { value("merged") }
			jsonPath("$.allowed[0]") { value("in_review") }
			jsonPath("$.allowed[1]") { value("closed") }
		}
		mockMvc.post("$BASE/21/status") { auth(); json("""{"status":"done"}""") }.andExpect { problem(400, "invalid_ticket") }
		mockMvc.post("$BASE/22/status") { auth(); json("""{"status":"open"}""") }.andExpect { problem(404, "ticket_not_found") }
		mockMvc.post("/api/projects/14/tickets/21/status") { auth(); json("""{"status":"open"}""") }.andExpect { problem(409, "project_archived") }
	}

	@Test
	fun `a ticket with a workspace answers its branch, folder and worktree`() {
		val ready = ticket().apply {
			branch = "bugs/3-login-fails"
			folder = "feature/bugs/3-login-fails"
			workspaceState = "ready"
		}
		given(tickets.get(12, 21)).willReturn(ready)
		given(tickets.worktreeOf(ready)).willReturn(TicketWorktree(9, "/repos/12.worktrees/bugs__3-login-fails", false))

		mockMvc.get("$BASE/21") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.workspace.state") { value("ready") }
			jsonPath("$.workspace.branch") { value("bugs/3-login-fails") }
			jsonPath("$.workspace.folder") { value("feature/bugs/3-login-fails") }
			jsonPath("$.workspace.worktree.id") { value(9) }
			jsonPath("$.workspace.worktree.path") { value("/repos/12.worktrees/bugs__3-login-fails") }
			jsonPath("$.workspace.worktree.missing") { value(false) }
		}
	}

	@Test
	fun `a failed workspace shows its error, and a retry answers the ticket or the refusal`() {
		val failed = ticket().apply {
			workspaceState = "failed"
			workspaceError = "The push was rejected."
		}
		given(tickets.retryWorkspace(12, 21)).willReturn(failed)
		given(tickets.retryWorkspace(12, 22)).willThrow(WorkspaceNotNeededException())
		given(tickets.retryWorkspace(14, 21)).willThrow(TicketProjectArchivedException())

		mockMvc.post("$BASE/21/workspace").andExpect { status { isUnauthorized() } }
		mockMvc.post("$BASE/21/workspace") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.workspace.state") { value("failed") }
			jsonPath("$.workspace.error") { value("The push was rejected.") }
		}
		mockMvc.post("$BASE/22/workspace") { auth() }.andExpect { problem(409, "workspace_not_needed") }
		mockMvc.post("/api/projects/14/tickets/21/workspace") { auth() }.andExpect { problem(409, "project_archived") }
	}

	@Test
	fun `tickets can't be deleted`() {
		mockMvc.delete("$BASE/21") { auth() }.andExpect { status { isMethodNotAllowed() } }
	}

	private fun MockHttpServletRequestDsl.json(body: String) {
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun MockMvcResultMatchersDsl.problem(status: Int, code: String, detail: String? = null) {
		status { isEqualTo(status) }
		jsonPath("$.code") { value(code) }
		if (detail != null) jsonPath("$.detail") { value(detail) }
	}

	private fun MockHttpServletRequestDsl.auth() {
		header("Authorization", "Bearer test-token")
	}

	private fun ticket() = Ticket().apply {
		id = 21
		projectId = 12
		number = 3
		type = TicketType.BUG
		title = "Login fails"
		description = "Safari only"
		priority = TicketPriority.HIGH
		status = TicketStatus.IN_PROGRESS
		createdAt = AT
		updatedAt = AT
	}

	private companion object {
		const val BASE = "/api/projects/12/tickets"
		val AT: Instant = Instant.parse("2026-11-06T10:00:00Z")
	}
}
