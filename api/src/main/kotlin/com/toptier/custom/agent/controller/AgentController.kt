package com.toptier.custom.agent.controller

import com.toptier.custom.agent.dto.AskAgentRequest
import com.toptier.custom.agent.dto.AskAgentResponse
import com.toptier.custom.agent.service.AgentService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/agent")
class AgentController(private val agentService: AgentService) {

	/** Asks the agent and waits for its whole reply; pass the returned sessionId to continue the conversation. */
	@PostMapping("/ask")
	fun ask(@RequestBody request: AskAgentRequest): AskAgentResponse {
		val message = request.message?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("message is required")
		val reply = agentService.ask(message, request.sessionId?.takeIf { it.isNotBlank() })
		return AskAgentResponse(reply.sessionId, reply.text)
	}
}
