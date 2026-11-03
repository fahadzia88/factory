package com.toptier.custom.agent.service

import com.toptier.custom.agent.exception.AgentTurnFailedException
import com.toptier.custom.agent.exception.AgentUnavailableException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Reads an eve session stream (NDJSON, stream version 27) line by line and works out the reply to one delivery.
 * Unknown fact types, progress types and fields are ignored, as eve's protocol requires.
 */
class AgentReplyCollector(
	private val sessionId: String,
	private val deliveryId: String,
	private val jsonMapper: JsonMapper,
) {

	/** Stored lines read so far: the `startIndex` to reconnect from. Transport records are not stored lines. */
	var linesRead = 0
		private set

	/** Reply text parts per turn, in stream order. */
	private val replyParts = mutableMapOf<String, MutableList<String>>()
	private val turns = mutableMapOf<String, SettledTurn>()
	/** The last failed model run's error per turn, used when the turn itself carries none. */
	private val modelErrors = mutableMapOf<String, String>()

	private var reply: String? = null
	private var failure: String? = null

	fun accept(line: String): StreamStep {
		if (line.isBlank()) return StreamStep.CONTINUE
		val node = try {
			jsonMapper.readTree(line)
		} catch (ex: JacksonException) {
			throw AgentUnavailableException("Malformed line in the agent's stream", cause = ex)
		}
		node.string("\$eve")?.let { return transport(it) }

		linesRead++
		node.get("facts")?.forEach { fact ->
			if (fact(fact)) return StreamStep.SETTLED
		}
		return StreamStep.CONTINUE
	}

	/** The reply once [accept] has returned [StreamStep.SETTLED]. */
	fun reply(): String {
		failure?.let { throw AgentTurnFailedException(it, sessionId) }
		return checkNotNull(reply) { "The delivery has not settled" }
	}

	private fun transport(record: String): StreamStep =
		when (record) {
			"stream.lease-ended" -> StreamStep.RECONNECT
			"stream.ended" -> StreamStep.STREAM_ENDED
			// Heartbeats and records added by later releases
			else -> StreamStep.CONTINUE
		}

	/** Applies one fact; true once this delivery's outcome is known. */
	private fun fact(fact: JsonNode): Boolean {
		val data = fact.get("data") ?: return false
		val turnId = fact.get("scope")?.string("turnId") ?: data.string("turnId")
		when (fact.string("type")) {
			"content.completed" ->
				if (turnId != null && data.string("kind") == "text" && data.string("phase") == "reply") {
					data.string("value")?.let { replyParts.getOrPut(turnId) { mutableListOf() } += it }
				}
			"model.settled" ->
				if (turnId != null && data.string("outcome") == "failed") {
					data.errorMessage()?.let { modelErrors[turnId] = it }
				}
			"turn.settled" ->
				if (turnId != null) turns[turnId] = SettledTurn(data.string("outcome"), data.errorMessage())
			"delivery.settled" ->
				if (data.string("deliveryId") == deliveryId) {
					settle(data)
					return true
				}
			"session.ended" -> {
				failure = "The agent session ended before answering" + (data.errorMessage()?.let { ": $it" } ?: "")
				return true
			}
		}
		return false
	}

	private fun settle(delivery: JsonNode) {
		val outcome = delivery.string("outcome")
		val turnId = delivery.string("turnId")
		if (outcome != "handled" || turnId == null) {
			failure = when (outcome) {
				"awaiting-input" -> "The agent is waiting for input (an approval or a question), which this API can't answer"
				else -> "The agent did not handle the message (outcome ${outcome ?: "unknown"})" +
					(delivery.string("reason")?.let { ": $it" } ?: "")
			}
			return
		}
		// eve writes a turn's turn.settled in the same line as, and before, the deliveries it answered
		val turn = turns[turnId]
		if (turn?.outcome != "completed") {
			val error = turn?.error ?: modelErrors[turnId]
			failure = "The agent's turn ${turn?.outcome ?: "did not complete"}" + (error?.let { ": $it" } ?: "")
			return
		}
		reply = replyParts[turnId].orEmpty().joinToString("\n\n")
	}

	private fun JsonNode.string(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()

	private fun JsonNode.errorMessage(): String? = get("error")?.string("message")

	private data class SettledTurn(val outcome: String?, val error: String?)
}

enum class StreamStep {
	/** Keep reading. */
	CONTINUE,

	/** The read ended; reconnect from [AgentReplyCollector.linesRead]. */
	RECONNECT,

	/** The session's stream is closed for good. */
	STREAM_ENDED,

	/** The delivery settled; read the result with [AgentReplyCollector.reply]. */
	SETTLED,
}
