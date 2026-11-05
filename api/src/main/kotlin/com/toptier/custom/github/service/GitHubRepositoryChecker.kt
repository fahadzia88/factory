package com.toptier.custom.github.service

import com.toptier.custom.github.config.GitHubProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

/** The outcome of asking GitHub whether a repository exists. */
enum class RepositoryCheck {
	/** GitHub answered and our token can see the repository. */
	FOUND,

	/** GitHub answered 404: the repository doesn't exist, or it is private and our token can't see it. */
	NOT_FOUND,

	/** Couldn't ask GitHub (no token, rate-limited, rejected token, error or timeout); save as unverified. */
	UNAVAILABLE,
}

/** Checks that a GitHub repository exists, for the project's repository field. */
@Service
class GitHubRepositoryChecker(
	private val gitHubRestClient: RestClient,
	private val gitHubProperties: GitHubProperties,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/** Asks GitHub for `owner/repo`. Never throws: anything but a clear answer is [RepositoryCheck.UNAVAILABLE]. */
	fun check(owner: String, repo: String): RepositoryCheck {
		if (gitHubProperties.token.isBlank()) {
			log.debug("app.github.token is blank; not checking {}/{}", owner, repo)
			return RepositoryCheck.UNAVAILABLE
		}
		return try {
			gitHubRestClient.get().uri("/repos/{owner}/{repo}", owner, repo).retrieve().toBodilessEntity()
			RepositoryCheck.FOUND
		} catch (_: HttpClientErrorException.NotFound) {
			RepositoryCheck.NOT_FOUND
		} catch (ex: RestClientResponseException) {
			when (ex.statusCode.value()) {
				HttpStatus.UNAUTHORIZED.value() -> log.warn("GitHub rejected app.github.token; saving {}/{} as unverified", owner, repo)
				HttpStatus.FORBIDDEN.value(), HttpStatus.TOO_MANY_REQUESTS.value() ->
					log.info("GitHub rate-limited the check for {}/{}; saving as unverified", owner, repo)
				else -> log.warn("GitHub answered {} for {}/{}; saving as unverified", ex.statusCode.value(), owner, repo)
			}
			RepositoryCheck.UNAVAILABLE
		} catch (ex: ResourceAccessException) {
			log.warn("Couldn't reach GitHub for {}/{}: {}; saving as unverified", owner, repo, ex.javaClass.simpleName)
			RepositoryCheck.UNAVAILABLE
		} catch (ex: RestClientException) {
			log.warn("GitHub check for {}/{} failed: {}; saving as unverified", owner, repo, ex.javaClass.simpleName)
			RepositoryCheck.UNAVAILABLE
		}
	}
}
