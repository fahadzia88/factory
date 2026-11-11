package com.toptier.custom.feature

import com.toptier.custom.feature.controller.FeatureChatController
import com.toptier.custom.feature.controller.FeatureExceptionHandler
import com.toptier.custom.feature.domain.FeatureMessage
import com.toptier.custom.feature.domain.MessageRole
import com.toptier.custom.feature.exception.AgentUnavailableException
import com.toptier.custom.feature.exception.BlankMessageException
import com.toptier.custom.feature.exception.ChatBusyException
import com.toptier.custom.feature.exception.FeatureNotFoundException
import com.toptier.custom.feature.dto.SendMessageRequest
import com.toptier.custom.feature.service.ChatItem
import com.toptier.custom.feature.service.FeatureChat
import com.toptier.custom.feature.service.FeatureQuestions
import com.toptier.custom.security.config.SecurityConfig
import com.toptier.custom.security.config.SecurityProperties
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
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
import kotlin.test.assertNull

@WebMvcTest(FeatureChatController::class)
@Import(SecurityConfig::class, FeatureExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class FeatureChatControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@MockitoBean
	lateinit var chat: FeatureChat

	@MockitoBean
	lateinit var questions: FeatureQuestions

	@Test
	fun `rejects requests without a token`() {
		mockMvc.get(BASE).andExpect { status { isUnauthorized() } }
		mockMvc.post(BASE) { json("""{"message":"hi"}""") }.andExpect { status { isUnauthorized() } }
		mockMvc.post("$BASE/cancel").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `lists the stored messages`() {
		given(chat.history(12, 21)).willReturn(listOf(ChatItem.Message(message(1, MessageRole.USER, "Hi")), ChatItem.Message(message(2, MessageRole.ASSISTANT, "Hello"))))

		mockMvc.get(BASE) { auth() }.andExpect {
			status { isOk() }
			jsonPath("$[0].role") { value("user") }
			jsonPath("$[0].text") { value("Hi") }
			jsonPath("$[1].role") { value("assistant") }
			jsonPath("$[1].createdAt") { value("2026-11-06T10:00:00Z") }
		}
	}

	@Test
	fun `history lists question sets as items, and answers are passed through`() {
		val set = com.toptier.custom.feature.domain.FeatureQuestion().apply {
			id = 7
			featureId = 21
			payload = """{"questions":[{"header":"Token"}]}"""
			status = com.toptier.custom.feature.domain.QuestionStatus.PENDING
			createdAt = Instant.parse("2026-11-06T10:00:01Z")
		}
		given(chat.history(12, 21)).willReturn(listOf(ChatItem.Message(message(1, MessageRole.ASSISTANT, "Which token?")), ChatItem.Questions(set)))
		given(questions.payloadOf(set)).willReturn(tools.jackson.databind.json.JsonMapper.builder().build().readTree(set.payload))
		val answered = SendMessageRequest(questionsId = 7, answers = listOf(com.toptier.custom.feature.dto.AnswerInput("Token", listOf("Keep"), null)))
		willAnswer { it.getArgument<OutputStream>(3).write("{\"type\":\"done\"}\n".toByteArray()) }
			.given(chat).send(eq(12L), eq(21L), eqRequest(answered), anyOutput())
		willThrow(com.toptier.custom.feature.exception.InvalidAnswersException("Question 1 has no option \"x\"."))
			.given(chat).send(eq(12L), eq(21L), eqRequest(SendMessageRequest(questionsId = 8)), anyOutput())
		willThrow(com.toptier.custom.feature.exception.QuestionsNotPendingException())
			.given(chat).send(eq(12L), eq(21L), eqRequest(SendMessageRequest(questionsId = 9)), anyOutput())

		mockMvc.get(BASE) { auth() }.andExpect {
			status { isOk() }
			jsonPath("$[0].kind") { value("message") }
			jsonPath("$[1].kind") { value("questions") }
			jsonPath("$[1].id") { value(7) }
			jsonPath("$[1].status") { value("pending") }
			jsonPath("$[1].questions[0].header") { value("Token") }
			jsonPath("$[1].answers") { value(null as Any?) }
		}
		mockMvc.post(BASE) { auth(); json("""{"questionsId":7,"answers":[{"header":"Token","selected":["Keep"],"other":null}]}""") }
			.andExpect { status { isOk() } }
		mockMvc.post(BASE) { auth(); json("""{"questionsId":8}""") }.andExpect { problem(400, "invalid_answers") }
		mockMvc.post(BASE) { auth(); json("""{"questionsId":9}""") }.andExpect { problem(409, "questions_not_pending") }
	}

	@Test
	fun `streams the agent's lines as NDJSON on the request thread`() {
		willAnswer {
			val out = it.getArgument<OutputStream>(3)
			out.write("{\"type\":\"session\",\"sessionId\":\"s\"}\n".toByteArray())
			out.flush()
			out.write("{\"type\":\"done\",\"sessionId\":\"s\",\"text\":\"ok\"}\n".toByteArray())
			out.flush()
		}.given(chat).send(eq(12L), eq(21L), eqRequest(SendMessageRequest("hi")), anyOutput())

		val result = mockMvc.post(BASE) { auth(); json("""{"message":"hi"}""") }.andExpect {
			status { isOk() }
			content { contentType("application/x-ndjson;charset=UTF-8") }
			header { string("Cache-Control", "no-cache") }
			header { doesNotExist("Content-Encoding") }
		}.andReturn()

		assertFalse(result.request.isAsyncStarted)
		assertEquals(
			"{\"type\":\"session\",\"sessionId\":\"s\"}\n{\"type\":\"done\",\"sessionId\":\"s\",\"text\":\"ok\"}\n",
			result.response.contentAsString,
		)
		assertNull(result.response.getHeader("Content-Encoding"))
	}

	@Test
	fun `errors before the stream are problems`() {
		willThrow(BlankMessageException()).given(chat).send(eq(12L), eq(21L), eqRequest(SendMessageRequest("")), anyOutput())
		willThrow(ChatBusyException()).given(chat).send(eq(12L), eq(21L), eqRequest(SendMessageRequest("busy")), anyOutput())
		willThrow(AgentUnavailableException("The agent can't be reached. Try again.")).given(chat).send(eq(12L), eq(21L), eqRequest(SendMessageRequest("down")), anyOutput())
		willThrow(FeatureNotFoundException()).given(chat).send(eq(12L), eq(22L), anyRequest(), anyOutput())

		mockMvc.post(BASE) { auth(); json("""{"message":""}""") }.andExpect { problem(400, "invalid_request") }
		mockMvc.post(BASE) { auth(); json("""{"message":"busy"}""") }.andExpect { problem(409, "chat_busy") }
		mockMvc.post(BASE) { auth(); json("""{"message":"down"}""") }
			.andExpect { problem(502, "agent_unavailable", "The agent can't be reached. Try again.") }
		mockMvc.post("/api/projects/12/features/22/messages") { auth(); json("""{"message":"x"}""") }
			.andExpect { problem(404, "feature_not_found") }
	}

	@Test
	fun `cancel answers whether a turn was stopped`() {
		given(chat.cancel(12, 21)).willReturn(true)

		mockMvc.post("$BASE/cancel") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.cancelled") { value(true) }
		}
	}

	/** Mockito's `any()` returns null, which a Kotlin non-null parameter refuses; the matcher is registered either way. */
	/** Mockito's `eq()` returns null for objects; the matcher is registered either way. */
	private fun eqRequest(request: SendMessageRequest): SendMessageRequest = eq(request) ?: request

	private fun anyRequest(): SendMessageRequest = any(SendMessageRequest::class.java) ?: SendMessageRequest()

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

	private fun message(id: Long, role: MessageRole, text: String) = FeatureMessage().apply {
		this.id = id
		featureId = 21
		this.role = role
		this.text = text
		createdAt = Instant.parse("2026-11-06T10:00:00Z")
	}

	private companion object {
		const val BASE = "/api/projects/12/features/21/messages"
	}
}
