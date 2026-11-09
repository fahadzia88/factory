package com.toptier.custom.feature.service

import com.toptier.custom.checkout.exception.describe
import com.toptier.custom.feature.config.FeatureProperties
import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureMessage
import com.toptier.custom.feature.domain.FeatureMessageRepository
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.domain.MessageRole
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
import java.util.concurrent.ConcurrentHashMap

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
	@Qualifier("featureAgentRestClient") private val client: RestClient,
	private val jsonMapper: JsonMapper,
	private val props: FeatureProperties,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/** Features with a turn open; a second message waits for the first. */
	private val busy = ConcurrentHashMap.newKeySet<Long>()

	/** The feature's chat, oldest first. */
	fun messages(projectId: Long, featureId: Long): List<FeatureMessage> {
		featureService.load(projectId, featureId)
		return messages.findByFeatureIdOrderByCreatedAtAscIdAsc(featureId)
	}

	/**
	 * Sends [raw] to the agent and writes its events to [out], one NDJSON line each, flushed as they come.
	 * Throws before writing anything when the message is blank, the chat is busy or the agent can't take it.
	 */
	fun send(projectId: Long, featureId: Long, raw: String?, out: OutputStream) {
		val feature = featureService.load(projectId, featureId)
		val text = raw?.trim()
		if (text.isNullOrEmpty()) throw BlankMessageException()
		if (!busy.add(featureId)) throw ChatBusyException()
		try {
			open(feature, text).use { stream ->
				messages.save(message(featureId, MessageRole.USER, text))
				relay(feature, stream, out)
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

	private fun relay(feature: Feature, stream: AgentChatStream, out: OutputStream) {
		var sessionId = feature.agentSessionId
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
			if (event.type == "done") messages.save(message(feature.id!!, MessageRole.ASSISTANT, event.text.orEmpty()))
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

	private fun message(featureId: Long, role: MessageRole, text: String) = FeatureMessage().apply {
		this.featureId = featureId
		this.role = role
		this.text = text
		createdAt = clock.instant()
	}
}
