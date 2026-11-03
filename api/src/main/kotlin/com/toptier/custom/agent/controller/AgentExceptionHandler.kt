package com.toptier.custom.agent.controller

import com.toptier.custom.agent.exception.AgentException
import com.toptier.custom.agent.exception.AgentSessionNotFoundException
import com.toptier.custom.agent.exception.AgentTimeoutException
import com.toptier.custom.agent.exception.AgentTurnFailedException
import com.toptier.custom.agent.exception.AgentUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(basePackageClasses = [AgentController::class])
class AgentExceptionHandler {

	private val log = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler(AgentException::class)
	fun handleAgent(ex: AgentException): ProblemDetail {
		val status = when (ex) {
			is AgentSessionNotFoundException -> HttpStatus.NOT_FOUND
			is AgentTimeoutException -> HttpStatus.GATEWAY_TIMEOUT
			// Not the caller's fault: the agent is down, misconfigured or its model call failed
			is AgentUnavailableException, is AgentTurnFailedException -> HttpStatus.BAD_GATEWAY
		}
		if (status == HttpStatus.NOT_FOUND) log.info(ex.message) else log.warn(ex.message, ex)
		return ProblemDetail.forStatusAndDetail(status, ex.message ?: "Agent request failed").apply {
			// The conversation can continue after a failed or slow turn
			val sessionId = when (ex) {
				is AgentTurnFailedException -> ex.sessionId
				is AgentTimeoutException -> ex.sessionId
				else -> null
			}
			sessionId?.let { setProperty("sessionId", it) }
		}
	}

	@ExceptionHandler(IllegalArgumentException::class)
	fun handleInvalidInput(ex: IllegalArgumentException): ProblemDetail =
		ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "Invalid request")
}
