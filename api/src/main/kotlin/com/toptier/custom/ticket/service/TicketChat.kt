package com.toptier.custom.ticket.service

import com.toptier.custom.checkout.exception.describe
import com.toptier.custom.feature.config.FeatureProperties
import com.toptier.custom.feature.domain.MessageRole
import com.toptier.custom.feature.exception.AgentUnavailableException
import com.toptier.custom.feature.exception.BlankMessageException
import com.toptier.custom.feature.exception.ChatBusyException
import com.toptier.custom.feature.service.AgentChatStream
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketMessage
import com.toptier.custom.ticket.domain.TicketMessageRepository
import com.toptier.custom.ticket.domain.TicketRepository
import com.toptier.custom.ticket.exception.TicketNotFoundException
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.WorkspaceMissingException
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
 * A ticket's chat with the agent, built like the feature chat: each message goes to the agent's `POST /agent/v1/chat`
 * with a context line naming the ticket, in the ticket's own session, and the agent's NDJSON is relayed as it arrives.
 * The user's messages and the agent's final replies are stored so the page can show the conversation again.
 * Needs a ready workspace, since the agent works in the ticket's worktree.
 */
@Service
class TicketChat(
	private val projects: ProjectService,
	private val tickets: TicketRepository,
	private val messages: TicketMessageRepository,
	@Qualifier("featureAgentRestClient") private val client: RestClient,
	private val jsonMapper: JsonMapper,
	private val props: FeatureProperties,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/** Tickets with a turn open; a second message waits for the first. */
	private val busy = ConcurrentHashMap.newKeySet<Long>()

	/** The ticket's chat, oldest first. Works without a workspace and for archived projects. */
	fun history(projectId: Long, ticketId: Long): List<TicketMessage> {
		val ticket = load(projectId, ticketId)
		return messages.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.id!!)
	}

	/**
	 * Sends the user's message to the agent and writes its events to [out], one NDJSON line each, flushed as they
	 * come. Throws before writing anything when the project is archived, the ticket has no workspace, the message
	 * is blank, the chat is busy or the agent can't take it.
	 */
	fun send(projectId: Long, ticketId: Long, message: String?, out: OutputStream) {
		val project = projects.get(projectId)
		val ticket = tickets.findByIdAndProjectId(ticketId, projectId) ?: throw TicketNotFoundException()
		if (project.archived) throw TicketProjectArchivedException()
		if (ticket.workspaceState != READY) throw WorkspaceMissingException()
		val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: throw BlankMessageException()
		if (!busy.add(ticketId)) throw ChatBusyException()
		try {
			open(ticket, text).use { stream ->
				messages.save(message(ticketId, MessageRole.USER, text))
				relay(ticket, stream, out)
			}
		} finally {
			busy.remove(ticketId)
		}
	}

	/** Asks the agent to stop the ticket's running turn; false when there's no session or nothing was running. */
	fun cancel(projectId: Long, ticketId: Long): Boolean {
		val sessionId = load(projectId, ticketId).agentSessionId ?: return false
		val body = agentCall {
			client.post().uri("/agent/v1/session/{id}/cancel", sessionId).retrieve().body(String::class.java)
		}
		return try {
			body?.let { jsonMapper.readTree(it).get("cancelled")?.asBoolean() } ?: false
		} catch (_: JacksonException) {
			false
		}
	}

	private fun load(projectId: Long, ticketId: Long): Ticket {
		projects.get(projectId)
		return tickets.findByIdAndProjectId(ticketId, projectId) ?: throw TicketNotFoundException()
	}

	private fun relay(ticket: Ticket, stream: AgentChatStream, out: OutputStream) {
		var sessionId = ticket.agentSessionId
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
				log.info("Ticket {}: lost the agent's stream: {}", ticket.id, ex.message)
				fail("The connection to the agent was lost.")
				return
			} ?: break
			if (event.type == "session" && event.sessionId != null && event.sessionId != sessionId) {
				sessionId = event.sessionId
				tickets.updateAgentSessionId(ticket.id!!, sessionId)
			}
			if (event.type == "done") messages.save(message(ticket.id!!, MessageRole.ASSISTANT, event.text.orEmpty()))
			if (!write(event.line)) return
			if (stream.terminal != null) return
		}
		fail("The agent stopped without answering.")
	}

	/** Starts the turn; a stored session the agent no longer has is dropped and the turn starts a new one. */
	private fun open(ticket: Ticket, text: String): AgentChatStream {
		val message = contextLine(ticket) + "\n\n" + text
		val sessionId = ticket.agentSessionId
		val response = post(message, sessionId)
		if (response.statusCode.value() == 200) return AgentChatStream(response, jsonMapper)
		val status = response.statusCode
		val code = errorCode(response)
		response.close()
		if (status.value() == 404 && code == "session_not_found" && sessionId != null) {
			tickets.updateAgentSessionId(ticket.id!!, null)
			ticket.agentSessionId = null
			return open(ticket, text)
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

	private fun contextLine(ticket: Ticket): String =
		"[Ticket context: projectId=${ticket.projectId} ticketId=${ticket.id} number=${ticket.number} type=${ticket.type.value}. " +
			"Use the ticket_* tools with these ids.]"

	private fun message(ticketId: Long, role: MessageRole, text: String) = TicketMessage().apply {
		this.ticketId = ticketId
		this.role = role
		this.text = text
		createdAt = clock.instant()
	}

	private companion object {
		const val READY = "ready"
	}
}
