package com.toptier.custom.agent.dto

data class AskAgentRequest(
	val message: String? = null,
	/** Continues this session; omit to start a new one. */
	val sessionId: String? = null,
)

data class AskAgentResponse(val sessionId: String, val reply: String)
