package com.toptier.custom.homelab.service

import com.toptier.custom.homelab.dto.ApplyObjectResult
import com.toptier.custom.homelab.exception.HomelabApiException
import com.toptier.custom.homelab.exception.HomelabException
import com.toptier.custom.homelab.exception.HomelabUnreachableException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.type.TypeReference

/** Turns what `RestClient` throws into the exceptions of this feature, keeping the controller's own message. */
object HomelabErrors {

	/** [error] as a [HomelabException]: the controller's answer, an unreachable controller, or [error] itself. */
	fun map(error: Throwable): HomelabException = when (error) {
		is HomelabException -> error
		is RestClientResponseException -> fromResponse(error.statusCode.value(), error.responseBodyAsString)
		is ResourceAccessException -> HomelabUnreachableException(describe(error))
		is RestClientException -> HomelabUnreachableException("The controller sent a response that couldn't be read.")
		else -> throw error
	}

	/** The controller's `{ "error": string }`, with `dryRun` and `results` for a failed apply; falls back to the text. */
	fun fromResponse(status: Int, body: String?): HomelabApiException {
		val text = body?.trim().orEmpty()
		val node = try {
			if (text.startsWith("{")) HomelabJson.mapper.readTree(text) else null
		} catch (_: Exception) {
			null
		}
		val error = node?.get("error")?.takeIf { it.isValueNode && !it.isNull }?.asString()?.takeIf { it.isNotBlank() }
		val message = error ?: text.takeIf { it.isNotEmpty() && node == null } ?: "The controller answered with status $status."
		val results = node?.get("results")?.takeIf { it.isArray }?.let {
			try {
				HomelabJson.mapper.readValue(it.toString(), object : TypeReference<List<ApplyObjectResult>>() {})
			} catch (_: Exception) {
				null
			}
		}
		val dryRun = node?.get("dryRun")?.takeIf { it.isBoolean }?.asBoolean()
		return HomelabApiException(status, message, dryRun, results)
	}

	/** The message of the root cause, which says what failed (timed out, refused, unknown host). */
	private fun describe(error: ResourceAccessException): String {
		var cause: Throwable = error
		while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
		val text = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
		return "Couldn't reach the controller: $text"
	}
}
