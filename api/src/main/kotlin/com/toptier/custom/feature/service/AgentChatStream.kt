package com.toptier.custom.feature.service

import org.springframework.http.client.ClientHttpResponse
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/** One event of the agent's NDJSON stream; [type] is null for a line that isn't a JSON object. */
data class AgentChatEvent(
	val line: String,
	val type: String?,
	val sessionId: String?,
	val text: String?,
	val name: String? = null,
	val status: String? = null,
)

/** Reads the agent's chat stream line by line. Closing it closes the connection, which cancels the turn on the agent. */
class AgentChatStream(private val response: ClientHttpResponse, private val jsonMapper: JsonMapper) : AutoCloseable {

	private val reader = response.body.bufferedReader(Charsets.UTF_8)

	/** The `done`, `error` or `cancelled` event, once read. */
	var terminal: AgentChatEvent? = null
		private set

	/** The next non-blank event, or null at the end of the stream; read errors are thrown as they happen. */
	fun next(): AgentChatEvent? {
		while (true) {
			val line = reader.readLine() ?: return null
			if (line.isBlank()) continue
			return parse(line).also { if (it.type in TERMINAL) terminal = it }
		}
	}

	override fun close() = response.close()

	private fun parse(line: String): AgentChatEvent {
		val node = try {
			jsonMapper.readTree(line)
		} catch (_: JacksonException) {
			null
		}
		if (node == null || !node.isObject) return AgentChatEvent(line, null, null, null)
		fun field(name: String) = node.get(name)?.takeIf { it.isString }?.asString()
		return AgentChatEvent(line, field("type"), field("sessionId"), field("text"), field("name"), field("status"))
	}

	private companion object {
		val TERMINAL = setOf("done", "error", "cancelled")
	}
}
