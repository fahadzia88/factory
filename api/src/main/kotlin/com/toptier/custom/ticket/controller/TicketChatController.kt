package com.toptier.custom.ticket.controller

import com.toptier.custom.ticket.dto.SendTicketMessageRequest
import com.toptier.custom.ticket.dto.TicketCancelResponse
import com.toptier.custom.ticket.dto.TicketMessageResponse
import com.toptier.custom.ticket.service.TicketChat
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.OutputStream

/** A ticket's chat with the agent: the stored conversation, a streamed turn, and stopping it. */
@RestController
@RequestMapping("/api/projects/{id}/tickets/{ticketId}")
class TicketChatController(
	private val chat: TicketChat,
) {

	/** The ticket's chat history, oldest first. */
	@GetMapping("/messages")
	fun list(@PathVariable id: Long, @PathVariable ticketId: Long): List<TicketMessageResponse> =
		chat.history(id, ticketId).map(TicketMessageResponse::from)

	/**
	 * Sends a message and streams the agent's events as NDJSON. It writes on the request thread, not
	 * through an async return type, so no MVC async timeout cuts a long turn; errors before the first
	 * line are ProblemDetail.
	 */
	@PostMapping("/messages")
	fun send(
		@PathVariable id: Long,
		@PathVariable ticketId: Long,
		@RequestBody request: SendTicketMessageRequest,
		response: HttpServletResponse,
	) {
		chat.send(id, ticketId, request.message, NdjsonOutput(response))
	}

	/** Stops the ticket's running turn. */
	@PostMapping("/cancel")
	fun cancel(@PathVariable id: Long, @PathVariable ticketId: Long): TicketCancelResponse =
		TicketCancelResponse(chat.cancel(id, ticketId))

	/** The servlet output, started with the NDJSON headers on the first write so earlier errors can still be ProblemDetail. */
	private class NdjsonOutput(private val response: HttpServletResponse) : OutputStream() {

		private val out: OutputStream by lazy {
			response.status = HttpServletResponse.SC_OK
			response.contentType = "application/x-ndjson;charset=UTF-8"
			response.setHeader("Cache-Control", "no-cache")
			response.setHeader("X-Accel-Buffering", "no")
			response.outputStream
		}

		override fun write(b: Int) = out.write(b)

		override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

		override fun flush() = out.flush()
	}
}
