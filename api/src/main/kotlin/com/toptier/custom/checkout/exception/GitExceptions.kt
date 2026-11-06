package com.toptier.custom.checkout.exception

import java.time.Duration

/** A git failure. Messages are safe to log and never contain the token. */
sealed class GitException(message: String) : RuntimeException(message)

/** A clone, fetch or push was asked for, but `app.git.token` is blank. */
class GitNotConfiguredException :
	GitException("The API has no git token configured. Set API_GIT_TOKEN in the root .env.")

/** A git command ran longer than [timeout] and was killed. */
class GitTimeoutException(val timeout: Duration) :
	GitException("git didn't finish within ${describe(timeout)}.")

/**
 * A git command exited non-zero. [command] is the subcommand only (`clone`, `fetch`, …), never the
 * full argument list; [stderr] is already scrubbed of the token.
 */
class GitCommandException(val command: String, val exitCode: Int, val stderr: String) :
	GitException("git $command failed (exit $exitCode)") {

	/** What kind of failure [stderr] describes. */
	val failure: GitFailure get() = GitFailure.of(stderr)
}

/** The kinds of git failure callers react to differently, read from git's (English) stderr. */
enum class GitFailure {
	NOT_FOUND, AUTH, REJECTED, STALE_LEASE, UNREACHABLE, OTHER;

	companion object {
		private val rejectedLine = Regex("""^\s*!\s*\[rejected]""", RegexOption.MULTILINE)
		private val repositoryNotFound = Regex("""fatal: repository '.*' not found""", RegexOption.IGNORE_CASE)

		/** Classifies [stderr]; the runner forces `LC_ALL=C`, so the messages are stable. */
		fun of(stderr: String): GitFailure {
			fun has(vararg needles: String) = needles.any { stderr.contains(it, ignoreCase = true) }
			return when {
				has("(stale info)") -> STALE_LEASE
				rejectedLine.containsMatchIn(stderr) || has("non-fast-forward", "(fetch first)") -> REJECTED
				has("Repository not found", "does not appear to be a git repository") ||
					repositoryNotFound.containsMatchIn(stderr) -> NOT_FOUND
				has(
					"Authentication failed", "could not read Username", "terminal prompts disabled",
					"returned error: 401", "returned error: 403",
				) -> AUTH
				has(
					"Could not resolve host", "Failed to connect", "Connection refused",
					"Connection timed out", "Operation timed out", "unable to access",
				) -> UNREACHABLE
				else -> OTHER
			}
		}
	}
}

/** "2 minutes" for whole minutes, seconds otherwise. */
internal fun describe(duration: Duration): String {
	val minutes = duration.toMinutes()
	return if (minutes > 0 && duration == Duration.ofMinutes(minutes)) {
		if (minutes == 1L) "1 minute" else "$minutes minutes"
	} else {
		val seconds = duration.toMillis() / 1000.0
		val text = if (seconds == Math.floor(seconds)) seconds.toLong().toString() else seconds.toString()
		if (text == "1") "1 second" else "$text seconds"
	}
}
