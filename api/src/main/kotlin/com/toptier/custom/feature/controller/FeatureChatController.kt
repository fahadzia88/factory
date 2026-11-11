package com.toptier.custom.feature.controller

import com.toptier.custom.feature.dto.CancelResponse
import com.toptier.custom.feature.dto.ChatItemResponse
import com.toptier.custom.feature.dto.QuestionsItemResponse
import com.toptier.custom.feature.dto.FeatureMessageResponse
import com.toptier.custom.feature.dto.SendMessageRequest
import com.toptier.custom.feature.service.ChatItem
import com.toptier.custom.feature.service.FeatureChat
import com.toptier.custom.feature.service.FeatureQuestions
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.OutputStream

/** A feature's chat with the agent: the stored conversation, a streamed turn, and stopping it. */
@RestController
@RequestMapping("/api/projects/{id}/features/{featureId}/messages")
class FeatureChatController(
	private val chat: FeatureChat,
	private val questions: FeatureQuestions,
) {

	/** The feature's chat history, oldest first: messages and the question sets asked in them. */
	@GetMapping
	fun list(@PathVariable id: Long, @PathVariable featureId: Long): List<ChatItemResponse> =
		chat.history(id, featureId).map { item ->
			when (item) {
				is ChatItem.Message -> FeatureMessageResponse.from(item.message)
				is ChatItem.Questions -> QuestionsItemResponse(
					id = item.set.id!!,
					status = item.set.status.name.lowercase(),
					questions = questions.payloadOf(item.set).get("questions"),
					answers = questions.answersOf(item.set),
					answerMessageId = item.set.answerMessageId,
					createdAt = item.set.createdAt,
				)
			}
		}

	/**
	 * Sends a message and streams the agent's events as NDJSON. It writes on the request thread, not
	 * through an async return type, so no MVC async timeout cuts a long turn; errors before the first
	 * line are ProblemDetail.
	 */
	@PostMapping
	fun send(
		@PathVariable id: Long,
		@PathVariable featureId: Long,
		@RequestBody request: SendMessageRequest,
		response: HttpServletResponse,
	) {
		chat.send(id, featureId, request, NdjsonOutput(response))
	}

	/** Stops the feature's running turn. */
	@PostMapping("/cancel")
	fun cancel(@PathVariable id: Long, @PathVariable featureId: Long): CancelResponse =
		CancelResponse(chat.cancel(id, featureId))

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
