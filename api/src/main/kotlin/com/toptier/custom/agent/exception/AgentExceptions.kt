package com.toptier.custom.agent.exception

import org.springframework.http.HttpStatusCode

/** A call to the eve agent did not produce a reply. */
sealed class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The agent could not be reached, rejected our credentials or answered with an unexpected error. */
class AgentUnavailableException(
	message: String,
	val upstreamStatus: HttpStatusCode? = null,
	cause: Throwable? = null,
) : AgentException(message, cause)

/** The session is unknown to the agent or has ended. */
class AgentSessionNotFoundException(val sessionId: String) :
	AgentException("Agent session $sessionId is unknown or has ended")

/** The agent took the message but its turn did not complete, e.g. the model call failed. */
class AgentTurnFailedException(message: String, val sessionId: String) : AgentException(message)

/** No settled reply within the configured timeout. The session, when known, may still answer later. */
class AgentTimeoutException(message: String, val sessionId: String? = null, cause: Throwable? = null) :
	AgentException(message, cause)
