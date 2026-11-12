package com.toptier.custom.ticket

import com.toptier.custom.feature.domain.MessageRole
import com.toptier.custom.feature.exception.AgentUnavailableException
import com.toptier.custom.feature.exception.BlankMessageException
import com.toptier.custom.feature.exception.ChatBusyException
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.security.config.SecurityConfig
import com.toptier.custom.security.config.SecurityProperties
import com.toptier.custom.ticket.controller.TicketChatController
import com.toptier.custom.ticket.controller.TicketExceptionHandler
import com.toptier.custom.ticket.domain.TicketMessage
import com.toptier.custom.ticket.exception.TicketNotFoundException
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.WorkspaceMissingException
import com.toptier.custom.ticket.service.TicketChat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willAnswer
import org.mockito.BDDMockito.willThrow
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@WebMvcTest(TicketChatController::class)
@Import(SecurityConfig::class, TicketExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class TicketChatControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@MockitoBean
	lateinit var chat: TicketChat

	@Test
	fun `rejects requests without a token`() {
		mockMvc.get(BASE).andExpect { status { isUnauthorized() } }
		mockMvc.post(BASE) { json("""{"message":"hi"}""") }.andExpect { status { isUnauthorized() } }
		mockMvc.post("/api/projects/12/tickets/21/cancel").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `lists the stored messages`() {
		given(chat.history(12, 21)).willReturn(listOf(message(1, MessageRole.USER, "Hi"), message(2, MessageRole.ASSISTANT, "Hello")))

		mockMvc.get(BASE) { auth() }.andExpect {
			status { isOk() }
			jsonPath("$[0].role") { value("user") }
			jsonPath("$[0].text") { value("Hi") }
			jsonPath("$[1].role") { value("assistant") }
			jsonPath("$[1].createdAt") { value("2026-11-06T10:00:00Z") }
		}
	}

	@Test
	fun `streams the agent's lines as NDJSON on the request thread`() {
		willAnswer {
			val out = it.getArgument<OutputStream>(3)
			out.write("{\"type\":\"session\",\"sessionId\":\"s\"}\n".toByteArray())
			out.flush()
			out.write("{\"type\":\"done\",\"sessionId\":\"s\",\"text\":\"ok\"}\n".toByteArray())
			out.flush()
		}.given(chat).send(eq(12L), eq(21L), eq("hi"), anyOutput())

		val result = mockMvc.post(BASE) { auth(); json("""{"message":"hi"}""") }.andExpect {
			status { isOk() }
			content { contentType("application/x-ndjson;charset=UTF-8") }
			header { string("Cache-Control", "no-cache") }
		}.andReturn()

		assertFalse(result.request.isAsyncStarted)
		assertEquals(
			"{\"type\":\"session\",\"sessionId\":\"s\"}\n{\"type\":\"done\",\"sessionId\":\"s\",\"text\":\"ok\"}\n",
			result.response.contentAsString,
		)
	}

	@Test
	fun `errors before the stream are problems`() {
		willThrow(BlankMessageException()).given(chat).send(eq(12L), eq(21L), eq(""), anyOutput())
		willThrow(ChatBusyException()).given(chat).send(eq(12L), eq(21L), eq("busy"), anyOutput())
		willThrow(AgentUnavailableException("The agent can't be reached. Try again.")).given(chat).send(eq(12L), eq(21L), eq("down"), anyOutput())
		willThrow(WorkspaceMissingException()).given(chat).send(eq(12L), eq(22L), eq("x"), anyOutput())
		willThrow(TicketProjectArchivedException()).given(chat).send(eq(12L), eq(23L), eq("x"), anyOutput())
		willThrow(TicketNotFoundException()).given(chat).send(eq(12L), eq(24L), eq("x"), anyOutput())
		willThrow(ProjectNotFoundException(13)).given(chat).send(eq(13L), eq(21L), eq("x"), anyOutput())

		mockMvc.post(BASE) { auth(); json("""{"message":""}""") }.andExpect { problem(400, "blank_message") }
		mockMvc.post(BASE) { auth(); json("""{"message":"busy"}""") }.andExpect { problem(409, "chat_busy") }
		mockMvc.post(BASE) { auth(); json("""{"message":"down"}""") }
			.andExpect { problem(502, "agent_unavailable", "The agent can't be reached. Try again.") }
		mockMvc.post("/api/projects/12/tickets/22/messages") { auth(); json("""{"message":"x"}""") }.andExpect { problem(409, "workspace_missing") }
		mockMvc.post("/api/projects/12/tickets/23/messages") { auth(); json("""{"message":"x"}""") }.andExpect { problem(409, "project_archived") }
		mockMvc.post("/api/projects/12/tickets/24/messages") { auth(); json("""{"message":"x"}""") }.andExpect { problem(404, "ticket_not_found") }
		mockMvc.post("/api/projects/13/tickets/21/messages") { auth(); json("""{"message":"x"}""") }.andExpect { problem(404, "project_not_found") }
		mockMvc.post(BASE) { auth(); json("{not json") }.andExpect { problem(400, "invalid_request") }
	}

	@Test
	fun `cancel answers whether a turn was stopped`() {
		given(chat.cancel(12, 21)).willReturn(true)

		mockMvc.post("/api/projects/12/tickets/21/cancel") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.cancelled") { value(true) }
		}
	}

	/** Mockito's `any()` returns null, which a Kotlin non-null parameter refuses; the matcher is registered either way. */
	private fun anyOutput(): OutputStream = any(OutputStream::class.java) ?: ByteArrayOutputStream()

	private fun MockMvcResultMatchersDsl.problem(status: Int, code: String, detail: String? = null) {
		status { isEqualTo(status) }
		jsonPath("$.code") { value(code) }
		if (detail != null) jsonPath("$.detail") { value(detail) }
	}

	private fun MockHttpServletRequestDsl.auth() {
		header("Authorization", "Bearer test-token")
	}

	private fun MockHttpServletRequestDsl.json(body: String) {
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun message(id: Long, role: MessageRole, text: String) = TicketMessage().apply {
		this.id = id
		ticketId = 21
		this.role = role
		this.text = text
		createdAt = Instant.parse("2026-11-06T10:00:00Z")
	}

	private companion object {
		const val BASE = "/api/projects/12/tickets/21/messages"
	}
}
