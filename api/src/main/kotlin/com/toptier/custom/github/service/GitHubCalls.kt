package com.toptier.custom.github.service

import com.toptier.custom.github.exception.GitHubNotFoundException
import com.toptier.custom.github.exception.GitHubRateLimitedException
import com.toptier.custom.github.exception.GitHubRefusedException
import com.toptier.custom.github.exception.GitHubTimeoutException
import com.toptier.custom.github.exception.GitHubUnreachableException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import java.net.SocketTimeoutException

private val log = LoggerFactory.getLogger("com.toptier.custom.github.service.GitHubCalls")

/** Runs one GitHub request for [path], turning every way it can fail into a typed exception. Never logs the token. */
fun <T> callGitHub(path: String, request: () -> T): T {
	try {
		return request()
	} catch (_: HttpClientErrorException.NotFound) {
		throw GitHubNotFoundException(path)
	} catch (ex: RestClientResponseException) {
		val status = ex.statusCode.value()
		val rateLimited = status == HttpStatus.TOO_MANY_REQUESTS.value() ||
			(status == HttpStatus.FORBIDDEN.value() && ex.responseHeaders?.getFirst("x-ratelimit-remaining") == "0")
		if (rateLimited) {
			log.info("GitHub rate-limited {}", path)
			throw GitHubRateLimitedException(path)
		}
		if (status == HttpStatus.UNAUTHORIZED.value()) log.warn("GitHub rejected the token for {}", path)
		else log.info("GitHub answered {} for {}", status, path)
		throw GitHubRefusedException(status, path)
	} catch (ex: ResourceAccessException) {
		if (generateSequence<Throwable>(ex) { it.cause }.any { it is SocketTimeoutException }) {
			log.info("GitHub timed out for {}", path)
			throw GitHubTimeoutException(path, ex)
		}
		log.warn("Couldn't reach GitHub for {}: {}", path, ex.javaClass.simpleName)
		throw GitHubUnreachableException(path, ex)
	}
}
