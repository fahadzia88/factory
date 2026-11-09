package com.toptier.custom.feature

import com.toptier.custom.checkout.CheckoutTestConfig
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeInspector
import com.toptier.custom.feature.config.FeatureProperties
import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.domain.MessageRole
import com.toptier.custom.feature.exception.AgentUnavailableException
import com.toptier.custom.feature.exception.BlankMessageException
import com.toptier.custom.feature.exception.ChatBusyException
import com.toptier.custom.feature.service.FeatureChat
import com.toptier.custom.feature.service.FeatureService
import com.toptier.custom.feature.service.WorkflowCodec
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
import com.toptier.custom.project.service.ProjectService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@Import(
	FeatureChat::class, FeatureService::class, WorkflowCodec::class, ProjectService::class, WorktreeInspector::class,
	GitRunner::class, CheckoutTestConfig::class, FeatureTestConfig::class, FeatureChatTests.AgentClientConfig::class,
)
@EnableConfigurationProperties(FeatureProperties::class)
class FeatureChatTests {

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
	lateinit var chat: FeatureChat

	@Autowired
	lateinit var server: MockRestServiceServer

	@Autowired
	lateinit var codec: WorkflowCodec

	@Autowired
	lateinit var jsonMapper: JsonMapper

	@Autowired
	lateinit var projectRepository: ProjectRepository

	@Autowired
	lateinit var features: FeatureRepository

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	lateinit var feature: Feature

	@BeforeEach
	fun feature() {
		server.reset()
		val project = projectRepository.saveAndFlush(Project().apply {
			name = "Chat ${System.nanoTime()}"
			nameKey = name.lowercase()
			repositoryUrl = "https://github.com/acme/${System.nanoTime()}"
			createdAt = START
			updatedAt = START
		})
		feature = features.saveAndFlush(Feature().apply {
			projectId = project.id!!
			number = 1
			name = "X"
			slug = "x"
			status = "requirements"
			workflow = codec.write(codec.read(FeatureFixtures.WORKFLOW))
			branch = "feat/001-x/00-docs"
			folder = "feature/001-x"
			createdAt = START
			updatedAt = START
		})
	}

	@Test
	fun `the first message carries the context line, and the reply is relayed and stored`() {
		val context = "[Feature context: projectId=${feature.projectId} featureId=${feature.id} code=001-x. Use the feature_* tools with these ids.]"
		server.expect(requestTo("http://agent/agent/v1/chat")).andExpect(method(HttpMethod.POST))
			.andExpect(jsonPath("$.sessionId").doesNotExist())
			.andExpect(jsonPath("$.message").value("$context\n\nWhich feature is this?"))
			.andRespond(ndjson(SESSION, TEXT_1, TEXT_2, DONE))

		val out = send("  Which feature is this? ")

		server.verify()
		assertEquals(listOf(SESSION, TEXT_1, TEXT_2, DONE).joinToString("\n", postfix = "\n"), out)
		assertEquals(SESSION_ID, features.findById(feature.id!!).get().agentSessionId)
		val stored = chat.messages(feature.projectId, feature.id!!)
		assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), stored.map { it.role })
		assertEquals(listOf("Which feature is this?", "Feature 001-x."), stored.map { it.text })
	}

	@Test
	fun `later messages continue the stored session`() {
		features.updateAgentSessionId(feature.id!!, SESSION_ID)
		server.expect(requestTo("http://agent/agent/v1/chat")).andExpect(jsonPath("$.sessionId").value(SESSION_ID))
			.andRespond(ndjson(SESSION, DONE))

		send("Again")

		server.verify()
	}

	@Test
	fun `a session the agent lost is dropped and a new one started`() {
		features.updateAgentSessionId(feature.id!!, "11111111-1111-1111-1111-111111111111")
		server.expect(requestTo("http://agent/agent/v1/chat")).andExpect(jsonPath("$.sessionId").exists())
			.andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
				.body("""{"ok":false,"code":"session_not_found","error":"No such session"}"""))
		server.expect(requestTo("http://agent/agent/v1/chat")).andExpect(jsonPath("$.sessionId").doesNotExist())
			.andRespond(ndjson(SESSION, DONE))

		send("Hello")

		server.verify()
		assertEquals(SESSION_ID, features.findById(feature.id!!).get().agentSessionId)
	}

	@Test
	fun `a busy agent session is refused before anything is written or stored`() {
		server.expect(requestTo("http://agent/agent/v1/chat")).andRespond(
			withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
				.body("""{"ok":false,"code":"session_busy","error":"busy"}"""),
		)
		val out = ByteArrayOutputStream()

		assertThrows<ChatBusyException> { chat.send(feature.projectId, feature.id!!, "Hi", out) }

		assertEquals(0, out.size())
		assertEquals(emptyList(), chat.messages(feature.projectId, feature.id!!))
	}

	@Test
	fun `an agent error status or an unreachable agent is unavailable`() {
		server.expect(ExpectedCount.once(), requestTo("http://agent/agent/v1/chat")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))
		server.expect(ExpectedCount.once(), requestTo("http://agent/agent/v1/chat")).andRespond { throw ConnectException("refused") }

		val first = assertThrows<AgentUnavailableException> { send("Hi") }
		val second = assertThrows<AgentUnavailableException> { send("Hi") }

		assertEquals("The agent answered 500 INTERNAL_SERVER_ERROR.", first.message)
		assertEquals("The agent can't be reached. Try again.", second.message)
	}

	@Test
	fun `a blank message is refused`() {
		assertThrows<BlankMessageException> { send("   ") }
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
		assertEquals(listOf(MessageRole.USER), chat.messages(feature.projectId, feature.id!!).map { it.role })
	}

	@Test
	fun `a stream without an end event gets an error line`() {
		server.expect(requestTo("http://agent/agent/v1/chat")).andRespond(ndjson(SESSION, TEXT_1))

		val lines = send("Hi").trim().lines()

		assertEquals(3, lines.size)
		assertEquals("The agent stopped without answering.", jsonMapper.readTree(lines[2]).get("message").asString())
	}

	@Test
	fun `error and unknown events are relayed unchanged, with no reply stored`() {
		val unknown = """{"type":"thinking","delta":"hmm"}"""
		val error = """{"type":"error","sessionId":"$SESSION_ID","message":"The model is overloaded"}"""
		server.expect(requestTo("http://agent/agent/v1/chat")).andRespond(ndjson(SESSION, unknown, error))

		val out = send("Hi")

		assertEquals(listOf(SESSION, unknown, error).joinToString("\n", postfix = "\n"), out)
		assertEquals(listOf(MessageRole.USER), chat.messages(feature.projectId, feature.id!!).map { it.role })
	}

	@Test
	fun `a second message while a turn is open is refused`() {
		var nested: Exception? = null
		val body = object : InputStream() {
			private val bytes = "$SESSION\n$DONE\n".toByteArray()
			private var i = 0

			override fun read(): Int {
				if (i == 0 && nested == null) {
					nested = runCatching { chat.send(feature.projectId, feature.id!!, "Second", ByteArrayOutputStream()) }.exceptionOrNull() as Exception?
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
		assertFalse(chat.cancel(feature.projectId, feature.id!!))

		features.updateAgentSessionId(feature.id!!, SESSION_ID)
		server.expect(requestTo("http://agent/agent/v1/session/$SESSION_ID/cancel")).andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""{"ok":true,"cancelled":true}""", MediaType.APPLICATION_JSON))

		assertTrue(chat.cancel(feature.projectId, feature.id!!))
		server.verify()
	}

	@Test
	fun `messages of an unknown feature are not found`() {
		assertNull(runCatching { chat.messages(feature.projectId, 999_999) }.getOrNull())
	}

	private fun send(text: String): String {
		val out = ByteArrayOutputStream()
		chat.send(feature.projectId, feature.id!!, text, out)
		return out.toString(Charsets.UTF_8)
	}

	private fun ndjson(vararg lines: String) =
		withSuccess(lines.joinToString("\n", postfix = "\n"), MediaType.parseMediaType("application/x-ndjson"))

	/** Serves [prefix], then throws [failure]. */
	private class FailingAfter(prefix: String, private val failure: Exception) : InputStream() {
		private val bytes = prefix.toByteArray()
		private var i = 0

		override fun read(): Int = if (i < bytes.size) bytes[i++].toInt() else throw failure
	}

	private companion object {
		const val SESSION_ID = "8f2b138e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"
		const val SESSION = """{"type":"session","sessionId":"$SESSION_ID"}"""
		const val TEXT_1 = """{"type":"text","delta":"Feature "}"""
		const val TEXT_2 = """{"type":"text","delta":"001-x."}"""
		const val DONE = """{"type":"done","sessionId":"$SESSION_ID","text":"Feature 001-x."}"""
	}
}
