package com.toptier.custom.feature.service

import com.toptier.custom.checkout.exception.describe
import com.toptier.custom.feature.config.FeatureProperties
import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureMessage
import com.toptier.custom.feature.domain.FeatureMessageRepository
import com.toptier.custom.feature.domain.FeatureQuestion
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.domain.MessageRole
import com.toptier.custom.feature.dto.SendMessageRequest
import com.toptier.custom.feature.exception.AgentUnavailableException
import com.toptier.custom.feature.exception.BlankMessageException
import com.toptier.custom.feature.exception.ChatBusyException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** One item of a feature's chat history. */
sealed interface ChatItem {
	val createdAt: Instant

	data class Message(val message: FeatureMessage) : ChatItem {
		override val createdAt: Instant get() = message.createdAt
	}

	data class Questions(val set: FeatureQuestion) : ChatItem {
		override val createdAt: Instant get() = set.createdAt
	}
}

/**
 * A feature's chat with the agent. Each message goes to the agent's `POST /agent/v1/chat` with a context
 * line naming the feature, in the feature's own session; the agent's NDJSON is relayed as it arrives.
 * The user's messages and the agent's final replies are stored so the page can show the conversation again.
 */
@Service
class FeatureChat(
	private val featureService: FeatureService,
	private val features: FeatureRepository,
	private val messages: FeatureMessageRepository,
	private val questions: FeatureQuestions,
	@Qualifier("featureAgentRestClient") private val client: RestClient,
	private val jsonMapper: JsonMapper,
	private val props: FeatureProperties,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/** Features with a turn open; a second message waits for the first. */
	private val busy = ConcurrentHashMap.newKeySet<Long>()

	/** The feature's chat, oldest first: each message followed by the question sets asked in it. */
	fun history(projectId: Long, featureId: Long): List<ChatItem> {
		featureService.load(projectId, featureId)
		val stored = messages.findByFeatureIdOrderByCreatedAtAscIdAsc(featureId)
		val ids = stored.mapNotNull { it.id }.toSet()
		val sets = questions.all(featureId)
		val attached = sets.filter { it.afterMessageId in ids }.groupBy { it.afterMessageId }
		val items = mutableListOf<ChatItem>()
		for (message in stored) {
			items += ChatItem.Message(message)
			attached[message.id].orEmpty().forEach { items += ChatItem.Questions(it) }
		}
		// Sets never attached to a reply (the turn failed or was stopped) go by time
		for (set in sets.filter { it.afterMessageId !in ids }) {
			val at = items.indexOfFirst { it.createdAt.isAfter(set.createdAt) }
			if (at < 0) items += ChatItem.Questions(set) else items.add(at, ChatItem.Questions(set))
		}
		return items
	}

	/**
	 * Sends the user's message, or their answers to the pending question set, to the agent and writes its events to
	 * [out], one NDJSON line each, flushed as they come. Throws before writing anything when the message is blank,
	 * the answers don't fit, the chat is busy or the agent can't take it.
	 */
	fun send(projectId: Long, featureId: Long, request: SendMessageRequest, out: OutputStream) {
		val feature = featureService.load(projectId, featureId)
		val answering = request.questionsId?.let { questions.requirePending(featureId, it) }
		val answers = answering?.let { AnswerFormatter.validate(questions.questionsOf(it), request.answers) }
		val text = if (answering != null) {
			AnswerFormatter.format(questions.questionsOf(answering), answers!!)
		} else {
			request.message?.trim()?.takeIf { it.isNotEmpty() } ?: throw BlankMessageException()
		}
		if (!busy.add(featureId)) throw ChatBusyException()
		try {
			val lastSet = questions.lastId(featureId)
			open(feature, text).use { stream ->
				val sent = messages.save(message(featureId, MessageRole.USER, text))
				if (answering != null) questions.answer(answering, answers!!, sent.id!!)
				else questions.answerPendingByTyping(featureId, sent.id!!)
				relay(feature, stream, out, lastSet)
			}
		} finally {
			busy.remove(featureId)
		}
	}

	/** Asks the agent to stop the feature's running turn; false when there's no session or nothing was running. */
	fun cancel(projectId: Long, featureId: Long): Boolean {
		val sessionId = featureService.load(projectId, featureId).agentSessionId ?: return false
		val body = agentCall {
			client.post().uri("/agent/v1/session/{id}/cancel", sessionId).retrieve().body(String::class.java)
		}
		return try {
			body?.let { jsonMapper.readTree(it).get("cancelled")?.asBoolean() } ?: false
		} catch (_: JacksonException) {
			false
		}
	}

	private fun relay(feature: Feature, stream: AgentChatStream, out: OutputStream, lastSet: Long) {
		var sessionId = feature.agentSessionId
		val turnSets = mutableListOf<Long>()
		fun write(line: String): Boolean = try {
			out.write("$line\n".toByteArray(Charsets.UTF_8))
			out.flush()
			true
		} catch (_: IOException) {
			// The app hung up; closing the agent's stream cancels the turn there
			false
		}
		fun fail(message: String) {
			val event = mapOf("type" to "error", "sessionId" to sessionId, "message" to message)
			write(jsonMapper.writeValueAsString(event))
		}

		while (true) {
			val event = try {
				stream.next()
			} catch (_: SocketTimeoutException) {
				fail("The agent didn't send anything for ${describe(props.chatIdleTimeout)}, so the turn was stopped.")
				return
			} catch (ex: IOException) {
				log.info("Feature {}: lost the agent's stream: {}", feature.id, ex.message)
				fail("The connection to the agent was lost.")
				return
			} ?: break
			if (event.type == "session" && event.sessionId != null && event.sessionId != sessionId) {
				sessionId = event.sessionId
				features.updateAgentSessionId(feature.id!!, sessionId)
			}
			if (event.type == "tool" && event.name == ASK_QUESTIONS && event.status == "completed") {
				// The agent's tool stored the set through the API; the app gets it ahead of the tool line
				val set = questions.createdAfter(feature.id!!, lastSet)?.takeIf { it.id !in turnSets }
				if (set != null) {
					turnSets += set.id!!
					val line = mapOf("type" to "questions", "id" to set.id, "questions" to questions.payloadOf(set).get("questions"))
					if (!write(jsonMapper.writeValueAsString(line))) return
				}
			}
			if (event.type == "done") {
				val reply = messages.save(message(feature.id!!, MessageRole.ASSISTANT, event.text.orEmpty()))
				questions.attach(turnSets, reply.id!!)
			}
			if (!write(event.line)) return
			if (stream.terminal != null) return
		}
		fail("The agent stopped without answering.")
	}

	/** Starts the turn; a stored session the agent no longer has is dropped and the turn starts a new one. */
	private fun open(feature: Feature, text: String): AgentChatStream {
		val message = contextLine(feature) + "\n\n" + text
		val sessionId = feature.agentSessionId
		val response = post(message, sessionId)
		if (response.statusCode.value() == 200) return AgentChatStream(response, jsonMapper)
		val status = response.statusCode
		val code = errorCode(response)
		response.close()
		if (status.value() == 404 && code == "session_not_found" && sessionId != null) {
			features.updateAgentSessionId(feature.id!!, null)
			feature.agentSessionId = null
			return open(feature, text)
		}
		throw when (status.value()) {
			HttpStatus.CONFLICT.value() -> ChatBusyException()
			401, 403 -> AgentUnavailableException("The agent rejected the API token.")
			else -> AgentUnavailableException("The agent answered $status.")
		}
	}

	private fun post(message: String, sessionId: String?): ClientHttpResponse {
		val body = buildMap {
			put("message", message)
			if (sessionId != null) put("sessionId", sessionId)
		}
		return agentCall {
			client.post()
				.uri("/agent/v1/chat")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange({ _, response -> response }, false)
		}
	}

	private fun errorCode(response: ClientHttpResponse): String? = try {
		jsonMapper.readTree(response.body.readAllBytes()).get("code")?.asString()
	} catch (_: Exception) {
		null
	}

	private fun <T> agentCall(call: () -> T): T = try {
		call()
	} catch (ex: ResourceAccessException) {
		log.info("Agent unreachable: {}", ex.message)
		throw AgentUnavailableException("The agent can't be reached. Try again.")
	} catch (ex: RestClientResponseException) {
		val status = ex.statusCode.value()
		throw AgentUnavailableException(
			if (status == 401 || status == 403) "The agent rejected the API token." else "The agent answered ${ex.statusCode}.",
		)
	}

	private fun contextLine(feature: Feature): String {
		val code = FeatureNaming.code(feature.number, feature.slug)
		return "[Feature context: projectId=${feature.projectId} featureId=${feature.id} code=$code. Use the feature_* tools with these ids.]"
	}

	private companion object {
		const val ASK_QUESTIONS = "mcp__app__ask_questions"
	}

	private fun message(featureId: Long, role: MessageRole, text: String) = FeatureMessage().apply {
		this.featureId = featureId
		this.role = role
		this.text = text
		createdAt = clock.instant()
	}
}
