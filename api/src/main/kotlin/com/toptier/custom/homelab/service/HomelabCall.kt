package com.toptier.custom.homelab.service

import com.toptier.custom.homelab.config.HomelabClients
import com.toptier.custom.homelab.exception.HomelabUnreachableException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference

/** One request to the controller: [path] is a template whose `{vars}` are encoded, [query] skips null values. */
data class HomelabRequest(
	val method: HttpMethod,
	val path: String,
	val vars: Map<String, String> = emptyMap(),
	val query: Map<String, Any?> = emptyMap(),
	val json: Any? = null,
	val text: String? = null,
)

/**
 * Sends [HomelabRequest]s with the cluster's client and maps every failure with [HomelabErrors]. Logs the cluster,
 * method, path template and outcome, never headers, bodies or query values.
 */
@Component
class HomelabCall(private val clients: HomelabClients) {

	private val log = LoggerFactory.getLogger(javaClass)

	/** Runs [request] against [address] and reads the JSON answer as [type]. */
	fun <T> call(address: String, request: HomelabRequest, type: TypeReference<T>): T {
		val body = send(address, request)
		if (body.isNullOrBlank()) throw HomelabUnreachableException(UNREADABLE)
		return try {
			HomelabJson.mapper.readValue(body, type)
		} catch (_: Exception) {
			throw HomelabUnreachableException(UNREADABLE)
		}
	}

	/** Runs [request] against [address] and returns the raw text of the answer (empty when there is none). */
	fun text(address: String, request: HomelabRequest): String = send(address, request).orEmpty()

	/** Runs [block], mapping what it throws; for calls that handle the response themselves. */
	fun <T> guard(block: () -> T): T =
		try {
			block()
		} catch (e: Exception) {
			throw HomelabErrors.map(e)
		}

	private fun send(address: String, request: HomelabRequest): String? =
		try {
			val spec = clients.json(address).method(request.method).uri { builder ->
				builder.path(request.path)
				request.query.forEach { (key, value) -> if (value != null) builder.queryParam(key, value) }
				builder.build(request.vars)
			}
			request.json?.let { spec.body(it) }
			request.text?.let { spec.header("Content-Type", "text/plain; charset=UTF-8").body(it) }
			spec.retrieve().body(String::class.java).also { log.debug("{} {} {}: ok", address, request.method, request.path) }
		} catch (e: Exception) {
			val mapped = HomelabErrors.map(e)
			log.info("{} {} {}: {}", address, request.method, request.path, mapped.status ?: "no answer")
			throw mapped
		}

	private companion object {
		const val UNREADABLE = "The controller sent a response that couldn't be read."
	}
}
