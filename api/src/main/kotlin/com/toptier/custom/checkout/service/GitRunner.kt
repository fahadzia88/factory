package com.toptier.custom.checkout.service

import com.toptier.custom.checkout.config.GitProperties
import com.toptier.custom.checkout.exception.GitCommandException
import com.toptier.custom.checkout.exception.GitNotConfiguredException
import com.toptier.custom.checkout.exception.GitTimeoutException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

/** The outcome of one git command; [stdout] and [stderr] are scrubbed of the token. */
data class GitResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs CLI git. Network commands get the token as an HTTP header through `GIT_CONFIG_*` env, so it
 * never appears in arguments or on disk; every command runs with a clean environment, a timeout,
 * and output scrubbed of the token.
 */
@Component
class GitRunner(private val props: GitProperties) {

	private val log = LoggerFactory.getLogger(GitRunner::class.java)

	private val headerValue: String? = props.token.takeIf { it.isNotBlank() }?.let {
		Base64.getEncoder().encodeToString("x-access-token:$it".toByteArray())
	}

	/**
	 * Runs `git <args>` in [dir] and returns its result; a non-zero exit doesn't throw. With
	 * [network], the token header is set, and a blank token throws [GitNotConfiguredException]
	 * before anything starts. Throws [GitTimeoutException] after [timeout], killing git and its children.
	 */
	fun run(dir: Path?, args: List<String>, network: Boolean = false, timeout: Duration = props.networkTimeout): GitResult {
		if (network) requireToken()
		val pb = ProcessBuilder(listOf("git") + args).directory(dir?.toFile())
		pb.environment().apply {
			clear()
			put("PATH", System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin")
			put("HOME", System.getenv("HOME") ?: "/root")
			put("LC_ALL", "C")
			put("GIT_TERMINAL_PROMPT", "0")
			if (network) {
				put("GIT_CONFIG_COUNT", "1")
				put("GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader")
				put("GIT_CONFIG_VALUE_0", "Authorization: Basic $headerValue")
			}
		}
		val started = System.nanoTime()
		val process = pb.start()
		process.outputStream.close()
		val stdout = CappedBuffer()
		val stderr = CappedBuffer()
		val readers = listOf(
			Thread.ofVirtual().start { stdout.drain(process.inputStream) },
			Thread.ofVirtual().start { stderr.drain(process.errorStream) },
		)
		if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
			process.descendants().forEach { it.destroyForcibly() }
			process.destroyForcibly()
			process.waitFor(5, TimeUnit.SECONDS)
			readers.forEach { it.join(Duration.ofSeconds(2)) }
			log.debug("git {} in {} timed out after {} ms", args.firstOrNull(), dir, elapsedMs(started))
			throw GitTimeoutException(timeout)
		}
		readers.forEach { it.join(Duration.ofSeconds(2)) }
		val exitCode = process.exitValue()
		log.debug("git {} in {} exited {} in {} ms", args.firstOrNull(), dir, exitCode, elapsedMs(started))
		return GitResult(exitCode, scrub(stdout.text()), scrub(stderr.text()))
	}

	/** As [run], but a non-zero exit throws [GitCommandException]; returns stdout. */
	fun runOrThrow(dir: Path?, args: List<String>, network: Boolean = false, timeout: Duration = props.networkTimeout): String {
		val result = run(dir, args, network, timeout)
		if (result.exitCode != 0) {
			throw GitCommandException(args.first(), result.exitCode, result.stderr.take(4000))
		}
		return result.stdout
	}

	/** True when [ref] resolves to a commit in the repository at [dir]. */
	fun refExists(dir: Path, ref: String): Boolean {
		val result = run(dir, listOf("rev-parse", "--verify", "--quiet", "$ref^{commit}"))
		return result.exitCode == 0 && result.stdout.isNotBlank()
	}

	/** True when git accepts [name] as a branch name as written (no `@{-1}`-style expansion). */
	fun isValidBranchName(name: String): Boolean {
		if (name.isBlank() || name.startsWith("-") || name.startsWith("@{")) return false
		val result = run(null, listOf("check-ref-format", "--branch", name))
		return result.exitCode == 0 && result.stdout.trim() == name
	}

	/** Throws [GitNotConfiguredException] when the token is blank, for callers that must refuse before writing anything. */
	fun requireToken() {
		if (props.token.isBlank()) throw GitNotConfiguredException()
	}

	private fun scrub(text: String): String {
		if (props.token.isBlank()) return text
		return text
			.replace("x-access-token:${props.token}", "***")
			.replace(headerValue!!, "***")
			.replace(props.token, "***")
	}

	private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000

	/** Collects up to 1 MiB of a stream, then keeps draining it so git never blocks on a full pipe. */
	private class CappedBuffer {
		private val out = ByteArrayOutputStream()

		fun drain(input: InputStream) {
			val buffer = ByteArray(8192)
			input.use {
				while (true) {
					val n = it.read(buffer)
					if (n < 0) break
					val room = LIMIT - out.size()
					if (room > 0) synchronized(out) { out.write(buffer, 0, minOf(n, room)) }
				}
			}
		}

		fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8) }

		companion object {
			const val LIMIT = 1024 * 1024
		}
	}
}
