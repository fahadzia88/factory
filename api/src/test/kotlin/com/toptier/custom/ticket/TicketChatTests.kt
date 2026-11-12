package com.toptier.custom.ticket

import com.toptier.custom.feature.config.FeatureProperties
import com.toptier.custom.feature.domain.MessageRole
import com.toptier.custom.feature.exception.AgentUnavailableException
import com.toptier.custom.feature.exception.BlankMessageException
import com.toptier.custom.feature.exception.ChatBusyException
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketMessageRepository
import com.toptier.custom.ticket.domain.TicketRepository
import com.toptier.custom.ticket.exception.TicketNotFoundException
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.WorkspaceMissingException
import com.toptier.custom.ticket.service.TicketChat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DataJpaTest
@Import(TicketChat::class, TicketTestConfig::class, TicketChatTests.AgentClientConfig::class)
@EnableConfigurationProperties(FeatureProperties::class)
class TicketChatTests {

	/** The agent client, bound to a mock server. */
	@TestConfiguration
	class AgentClientConfig {
		private val builder = RestClient.builder().baseUrl("http://agent")
		private val server = MockRestServiceServer.bindTo(builder).build()

		@Bean
		fun agentServer(): MockRestServiceServer = server

		@Bean
		fun featureAgentRestClient(): RestClient = builder.build()
	}

	@Autowired
	lateinit var chat: TicketChat

	@Autowired
	lateinit var server: MockRestServiceServer

	@Autowired
	lateinit var jsonMapper: JsonMapper

	@Autowired
	lateinit var tickets: TicketRepository

	@Autowired
	lateinit var messages: TicketMessageRepository

	@MockitoBean
	lateinit var projects: ProjectService

	lateinit var ticket: Ticket

	@BeforeEach
	fun ticket() {
		server.reset()
		given(projects.get(1L)).willReturn(Project().apply { id = 1 })
		given(projects.get(2L)).willReturn(Project().apply { id = 2 })
		given(projects.get(3L)).willReturn(Project().apply { id = 3; archived = true })
		given(projects.get(9L)).willThrow(ProjectNotFoundException(9))
		ticket = tickets.saveAndFlush(newTicket(projectId = 1, number = 3, workspaceState = "ready"))
	}

	@Test
	fun `the first message carries the context line, and the reply is relayed and stored`() {
		val context = "[Ticket context: projectId=1 ticketId=${ticket.id} number=3 type=bug. Use the ticket_* tools with these ids.]"
		server.expect(requestTo("http://agent/agent/v1/chat")).andExpect(method(HttpMethod.POST))
			.andExpect(jsonPath("$.sessionId").doesNotExist())
			.andExpect(jsonPath("$.message").value("$context\n\nWhat is wrong?"))
			.andRespond(ndjson(SESSION, TEXT_1, TEXT_2, DONE))

		val out = send("  What is wrong? ")

		server.verify()
		assertEquals(listOf(SESSION, TEXT_1, TEXT_2, DONE).joinToString("\n", postfix = "\n"), out)
		assertEquals(SESSION_ID, tickets.findById(ticket.id!!).get().agentSessionId)
		val stored = chat.history(1, ticket.id!!)
		assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), stored.map { it.role })
		assertEquals(listOf("What is wrong?", "Found it."), stored.map { it.text })
	}

	@Test
	fun `later messages continue the stored session`() {
		tickets.updateAgentSessionId(ticket.id!!, SESSION_ID)
		server.expect(requestTo("http://agent/agent/v1/chat")).andExpect(jsonPath("$.sessionId").value(SESSION_ID))
			.andRespond(ndjson(SESSION, DONE))

		send("Again")

		server.verify()
	}

	@Test
	fun `a session the agent lost is dropped and a new one started`() {
		tickets.updateAgentSessionId(ticket.id!!, "11111111-1111-1111-1111-111111111111")
		server.expect(requestTo("http://agent/agent/v1/chat")).andExpect(jsonPath("$.sessionId").exists())
			.andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
				.body("""{"ok":false,"code":"session_not_found","error":"No such session"}"""))
		server.expect(requestTo("http://agent/agent/v1/chat")).andExpect(jsonPath("$.sessionId").doesNotExist())
			.andRespond(ndjson(SESSION, DONE))

		send("Hello")

		server.verify()
		assertEquals(SESSION_ID, tickets.findById(ticket.id!!).get().agentSessionId)
	}

	@Test
	fun `a busy agent session is refused before anything is written or stored`() {
		server.expect(requestTo("http://agent/agent/v1/chat")).andRespond(
			withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
				.body("""{"ok":false,"code":"session_busy","error":"busy"}"""),
		)
		val out = ByteArrayOutputStream()

		assertThrows<ChatBusyException> { chat.send(1, ticket.id!!, "Hi", out) }

		assertEquals(0, out.size())
		assertEquals(0, messages.count())
	}

	@Test
	fun `an agent error status or an unreachable agent is unavailable`() {
		server.expect(ExpectedCount.once(), requestTo("http://agent/agent/v1/chat")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))
		server.expect(ExpectedCount.once(), requestTo("http://agent/agent/v1/chat")).andRespond { throw ConnectException("refused") }

		val first = assertThrows<AgentUnavailableException> { send("Hi") }
		val second = assertThrows<AgentUnavailableException> { send("Hi") }

		assertEquals("The agent answered 500 INTERNAL_SERVER_ERROR.", first.message)
		assertEquals("The agent can't be reached. Try again.", second.message)
		assertEquals(0, messages.count())
	}

	@Test
	fun `a blank message is refused`() {
		assertThrows<BlankMessageException> { send("   ") }
		assertThrows<BlankMessageException> { chat.send(1, ticket.id!!, null, ByteArrayOutputStream()) }
	}

	@Test
	fun `a ticket without a ready workspace can't be chatted with, but its history can be read`() {
		val never = tickets.saveAndFlush(newTicket(projectId = 1, number = 4, workspaceState = "none"))
		val failed = tickets.saveAndFlush(newTicket(projectId = 1, number = 5, workspaceState = "failed"))

		assertThrows<WorkspaceMissingException> { chat.send(1, never.id!!, "Hi", ByteArrayOutputStream()) }
		assertThrows<WorkspaceMissingException> { chat.send(1, failed.id!!, "Hi", ByteArrayOutputStream()) }

		assertEquals(emptyList(), chat.history(1, never.id!!))
	}

	@Test
	fun `an archived project's chat can be read but not continued`() {
		val old = tickets.saveAndFlush(newTicket(projectId = 3, number = 1, workspaceState = "ready"))
		messages.saveAndFlush(com.toptier.custom.ticket.domain.TicketMessage().apply {
			ticketId = old.id!!
			role = MessageRole.USER
			text = "Earlier"
			createdAt = TicketTestConfig.START
		})

		assertThrows<TicketProjectArchivedException> { chat.send(3, old.id!!, "Hi", ByteArrayOutputStream()) }

		assertEquals(listOf("Earlier"), chat.history(3, old.id!!).map { it.text })
	}

	@Test
	fun `another project's ticket and unknown ones are not found`() {
		assertThrows<TicketNotFoundException> { chat.history(2, ticket.id!!) }
		assertThrows<TicketNotFoundException> { chat.send(2, ticket.id!!, "Hi", ByteArrayOutputStream()) }
		assertThrows<TicketNotFoundException> { chat.cancel(2, ticket.id!!) }
		assertThrows<TicketNotFoundException> { chat.history(1, ticket.id!! + 100) }
		assertThrows<ProjectNotFoundException> { chat.history(9, ticket.id!!) }
	}

	@Test
	fun `an idle stream ends with one error line and stores no reply`() {
		server.expect(requestTo("http://agent/agent/v1/chat")).andRespond {
			MockClientHttpResponse(FailingAfter("$SESSION\n", SocketTimeoutException("Read timed out")), HttpStatus.OK)
		}

		val lines = send("Slow").trim().lines()

		assertEquals(2, lines.size)
		assertEquals(SESSION, lines[0])
		val error = jsonMapper.readTree(lines[1])
		assertEquals("error", error.get("type").asString())
		assertEquals(SESSION_ID, error.get("sessionId").asString())
		assertEquals("The agent didn't send anything for 5 minutes, so the turn was stopped.", error.get("message").asString())
		assertEquals(listOf(MessageRole.USER), chat.history(1, ticket.id!!).map { it.role })
	}

	@Test
	fun `a stream without an end event gets an error line`() {
		server.expect(requestTo("http://agent/agent/v1/chat")).andRespond(ndjson(SESSION, TEXT_1))

		val lines = send("Hi").trim().lines()

		assertEquals(3, lines.size)
		assertEquals("The agent stopped without answering.", jsonMapper.readTree(lines[2]).get("message").asString())
	}

	@Test
	fun `a second message while a turn is open is refused`() {
		var nested: Exception? = null
		val body = object : InputStream() {
			private val bytes = "$SESSION\n$DONE\n".toByteArray()
			private var i = 0

			override fun read(): Int {
				if (i == 0 && nested == null) {
					nested = runCatching { chat.send(1, ticket.id!!, "Second", ByteArrayOutputStream()) }.exceptionOrNull() as Exception?
				}
				return if (i < bytes.size) bytes[i++].toInt() else -1
			}
		}
		server.expect(ExpectedCount.once(), requestTo("http://agent/agent/v1/chat")).andRespond { MockClientHttpResponse(body, HttpStatus.OK) }

		send("First")

		assertTrue(nested is ChatBusyException, "got $nested")
		server.verify()
	}

	@Test
	fun `cancel asks the agent only when there's a session`() {
		assertFalse(chat.cancel(1, ticket.id!!))

		tickets.updateAgentSessionId(ticket.id!!, SESSION_ID)
		server.expect(requestTo("http://agent/agent/v1/session/$SESSION_ID/cancel")).andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""{"ok":true,"cancelled":true}""", MediaType.APPLICATION_JSON))

		assertTrue(chat.cancel(1, ticket.id!!))
		server.verify()
	}

	private fun send(text: String): String {
		val out = ByteArrayOutputStream()
		chat.send(1, ticket.id!!, text, out)
		return out.toString(Charsets.UTF_8)
	}

	private fun ndjson(vararg lines: String) =
		withSuccess(lines.joinToString("\n", postfix = "\n"), MediaType.parseMediaType("application/x-ndjson"))

	private fun newTicket(projectId: Long, number: Int, workspaceState: String) = Ticket().apply {
		this.projectId = projectId
		this.number = number
		title = "Crash"
		this.workspaceState = workspaceState
		createdAt = TicketTestConfig.START
		updatedAt = TicketTestConfig.START
	}

	/** Serves [prefix], then throws [failure]. */
	private class FailingAfter(prefix: String, private val failure: Exception) : InputStream() {
		private val bytes = prefix.toByteArray()
		private var i = 0

		override fun read(): Int = if (i < bytes.size) bytes[i++].toInt() else throw failure
	}

	private companion object {
		const val SESSION_ID = "8f2b138e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"
		const val SESSION = """{"type":"session","sessionId":"$SESSION_ID"}"""
		const val TEXT_1 = """{"type":"text","delta":"Found "}"""
		const val TEXT_2 = """{"type":"text","delta":"it."}"""
		const val DONE = """{"type":"done","sessionId":"$SESSION_ID","text":"Found it."}"""
	}
}
