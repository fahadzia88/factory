package com.toptier.custom.agent.service

import com.toptier.custom.agent.config.AgentProperties
import com.toptier.custom.agent.exception.AgentException
import com.toptier.custom.agent.exception.AgentSessionNotFoundException
import com.toptier.custom.agent.exception.AgentTimeoutException
import com.toptier.custom.agent.exception.AgentTurnFailedException
import com.toptier.custom.agent.exception.AgentUnavailableException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class AgentReply(val sessionId: String, val text: String)

/**
 * Asks the eve agent (see agent/README.md, "HTTP API") for an answer: sends a message to a new or existing
 * session, then reads the session's stream until the message's delivery settles.
 */
@Service
class AgentService(
	private val agentRestClient: RestClient,
	private val jsonMapper: JsonMapper,
	private val agentProperties: AgentProperties,
	private val clock: Clock,
) {

	/** Waits between retries of a session that isn't ready yet; replaceable in tests. */
	internal var sleeper: (Duration) -> Unit = { Thread.sleep(it) }

	/** Sends [message], continuing [sessionId] when given, and returns the agent's reply. */
	fun ask(message: String, sessionId: String? = null): AgentReply {
		require(message.isNotBlank()) { "message is required" }
		sessionId?.let { require(SESSION_ID_PATTERN.matches(it)) { "Invalid sessionId" } }
		val deadline = clock.instant().plus(agentProperties.timeout)

		val receipt = if (sessionId == null) createSession(message) else sendFollowUp(sessionId, message, deadline)
		val collector = AgentReplyCollector(receipt.sessionId, receipt.deliveryId, jsonMapper)
		while (true) {
			// A read ends on a lease, or without a transport record if the connection drops: either way, resume
			when (readStream(receipt.sessionId, collector, deadline)) {
				StreamStep.SETTLED -> return AgentReply(receipt.sessionId, collector.reply())
				StreamStep.STREAM_ENDED -> throw AgentTurnFailedException("The agent session ended before answering", receipt.sessionId)
				StreamStep.RECONNECT, StreamStep.CONTINUE -> checkDeadline(deadline, receipt.sessionId)
			}
		}
	}

	private fun createSession(message: String): Receipt {
		val body = call("create a session") {
			agentRestClient.post()
				.uri("/eve/v1/session")
				.contentType(MediaType.APPLICATION_JSON)
				.body(mapOf("message" to message))
				.retrieve()
				.body(String::class.java)
		}
		return receipt(body, sessionId = null)
	}

	private fun sendFollowUp(sessionId: String, message: String, deadline: Instant): Receipt {
		// A session just created may not accept input yet; eve's own client retries for up to 20 seconds
		val retryUntil = minOf(deadline, clock.instant().plus(NOT_READY_WINDOW))
		var backoff = INITIAL_BACKOFF
		while (true) {
			try {
				val body = call("send a message") {
					agentRestClient.post()
						.uri("/eve/v1/session/{id}", sessionId)
						.contentType(MediaType.APPLICATION_JSON)
						.body(mapOf("message" to message))
						.retrieve()
						.body(String::class.java)
				}
				return receipt(body, sessionId)
			} catch (ex: AgentUnavailableException) {
				val code = (ex.cause as? RestClientResponseException)?.let { errorCode(it) }
				when {
					code == "session_not_active" -> throw AgentSessionNotFoundException(sessionId)
					code != "session_not_ready" -> throw ex
					clock.instant().plus(backoff).isAfter(retryUntil) ->
						throw AgentTimeoutException("Agent session $sessionId did not become ready", sessionId)
				}
				sleeper(backoff)
				backoff = backoff.multipliedBy(2).coerceAtMost(MAX_BACKOFF)
			}
		}
	}

	private fun readStream(sessionId: String, collector: AgentReplyCollector, deadline: Instant): StreamStep =
		call("read the session stream", sessionId) {
			agentRestClient.get()
				.uri("/eve/v1/session/{id}/stream?startIndex={start}", sessionId, collector.linesRead)
				.exchange { _, response ->
					if (!response.statusCode.is2xxSuccessful) {
						throw RestClientResponseException(
							"Stream request failed",
							response.statusCode,
							response.statusText,
							response.headers,
							response.body.readAllBytes(),
							null,
						)
					}
					response.body.bufferedReader().use { reader ->
						reader.lineSequence()
							.map { line -> collector.accept(line).also { if (it == StreamStep.CONTINUE) checkDeadline(deadline, sessionId) } }
							.firstOrNull { it != StreamStep.CONTINUE }
							?: StreamStep.CONTINUE
					}
				}
		} ?: StreamStep.CONTINUE

	/** Timeouts carry the session so the caller can continue the conversation once the agent catches up. */
	private fun checkDeadline(deadline: Instant, sessionId: String) {
		if (clock.instant().isAfter(deadline)) {
			throw AgentTimeoutException("The agent did not answer within ${agentProperties.timeout.toSeconds()}s", sessionId)
		}
	}

	private fun <T> call(action: String, sessionId: String? = null, request: () -> T): T =
		try {
			request()
		} catch (ex: AgentException) {
			throw ex
		} catch (ex: RestClientResponseException) {
			val status = ex.statusCode
			val detail = when (status.value()) {
				401, 403 -> "the agent rejected the API token"
				else -> "the agent answered $status" + (errorMessage(ex)?.let { ": $it" } ?: "")
			}
			throw AgentUnavailableException("Failed to $action: $detail", status, ex)
		} catch (ex: ResourceAccessException) {
			if (ex.cause is SocketTimeoutException) {
				throw AgentTimeoutException("Failed to $action: the agent stopped responding", sessionId, ex)
			}
			throw AgentUnavailableException("Failed to $action: the agent is unreachable at ${agentProperties.url}", cause = ex)
		}

	private fun receipt(body: String?, sessionId: String?): Receipt {
		val node = body?.let {
			try {
				jsonMapper.readTree(it)
			} catch (_: JacksonException) {
				null
			}
		}
		val id = sessionId ?: node?.get("sessionId")?.asString()
		val deliveryId = node?.get("deliveryId")?.asString()
		if (id.isNullOrBlank() || deliveryId.isNullOrBlank()) {
			throw AgentUnavailableException("The agent's receipt has no sessionId or deliveryId")
		}
		return Receipt(id, deliveryId)
	}

	/** eve's error bodies look like {"ok":false,"code":"session_not_ready","error":"..."}. */
	private fun errorCode(ex: RestClientResponseException): String? =
		ex.takeIf { it.statusCode.isSameCodeAs(HttpStatus.CONFLICT) }?.let { errorField(it, "code") }

	private fun errorMessage(ex: RestClientResponseException): String? = errorField(ex, "error")

	private fun errorField(ex: RestClientResponseException, field: String): String? =
		try {
			ex.responseBodyAsString.takeIf { it.isNotBlank() }
				?.let { jsonMapper.readTree(it).get(field) }
				?.takeIf { it.isString }
				?.asString()
		} catch (_: JacksonException) {
			null
		}

	private data class Receipt(val sessionId: String, val deliveryId: String)

	companion object {
		private val SESSION_ID_PATTERN = Regex("[A-Za-z0-9_\\-]{1,128}")
		private val NOT_READY_WINDOW = Duration.ofSeconds(20)
		private val INITIAL_BACKOFF = Duration.ofMillis(250)
		private val MAX_BACKOFF = Duration.ofSeconds(2)
	}
}
