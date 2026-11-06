package com.toptier.custom.github.exception

/** A GitHub call that didn't give an answer the caller can use. */
sealed class GitHubException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** GitHub answered 404: the resource doesn't exist, or the token can't see it. */
class GitHubNotFoundException(path: String) : GitHubException("GitHub has no $path")

/** GitHub refused the request: a rejected token, a 403 that isn't a rate limit, another 4xx, or a 5xx. */
class GitHubRefusedException(val status: Int, path: String) : GitHubException("GitHub answered $status for $path")

/** GitHub's rate limit was reached (429, or 403 with no requests remaining). */
class GitHubRateLimitedException(path: String) : GitHubException("GitHub rate-limited $path")

/** `app.github.token` is blank, so no call was made. */
class GitHubNotConfiguredException : GitHubException("app.github.token is blank")

/** GitHub didn't answer within `app.github.timeout`. */
class GitHubTimeoutException(path: String, cause: Throwable) : GitHubException("GitHub timed out for $path", cause)

/** The connection to GitHub failed for a reason other than a timeout. */
class GitHubUnreachableException(path: String, cause: Throwable) : GitHubException("Couldn't reach GitHub for $path", cause)
