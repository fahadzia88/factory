package com.toptier.custom.checkout

import com.toptier.custom.checkout.GitFixtures.bareRemote
import com.toptier.custom.checkout.GitFixtures.fileUrl
import com.toptier.custom.checkout.config.GitProperties
import com.toptier.custom.checkout.exception.GitCommandException
import com.toptier.custom.checkout.exception.GitFailure
import com.toptier.custom.checkout.exception.GitNotConfiguredException
import com.toptier.custom.checkout.exception.GitTimeoutException
import com.toptier.custom.checkout.service.GitRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitRunnerTests {

	@TempDir
	lateinit var tmp: Path

	private val runner by lazy { GitRunner(GitProperties(token = TOKEN, root = tmp.toString())) }

	private fun clone(): Path {
		val remote = bareRemote(tmp)
		val dir = tmp.resolve("clone")
		runner.runOrThrow(null, listOf("clone", fileUrl(remote), dir.toString()), network = true)
		return dir
	}

	@Test
	fun `runOrThrow returns stdout`() {
		val dir = clone()

		assertEquals("main", runner.runOrThrow(dir, listOf("rev-parse", "--abbrev-ref", "HEAD")).trim())
	}

	@Test
	fun `a non-zero exit is returned by run and thrown by runOrThrow`() {
		val dir = clone()

		val result = runner.run(dir, listOf("rev-parse", "--verify", "nope"))
		assertTrue(result.exitCode != 0)

		val e = assertThrows<GitCommandException> { runner.runOrThrow(dir, listOf("rev-parse", "--verify", "nope")) }
		assertEquals("rev-parse", e.command)
		assertEquals(result.exitCode, e.exitCode)
		assertTrue(e.stderr.isNotBlank())
		assertEquals("git rev-parse failed (exit ${e.exitCode})", e.message)
	}

	@Test
	fun `the auth header is set by env for network commands only`() {
		val key = listOf("config", "--get", "http.https://github.com/.extraheader")

		val withToken = runner.run(null, key, network = true)
		assertEquals(0, withToken.exitCode)
		assertEquals("Authorization: Basic ***", withToken.stdout.trim())
		assertFalse(withToken.stdout.contains(TOKEN))
		assertFalse(withToken.stdout.contains(base64))

		val without = runner.run(null, key, network = false)
		assertEquals(1, without.exitCode)
		assertEquals("", without.stdout)
	}

	@Test
	fun `token text in git output is scrubbed`() {
		val e = assertThrows<GitCommandException> {
			runner.runOrThrow(null, listOf("ls-remote", "file://$tmp/$TOKEN/missing.git"), network = true)
		}

		assertFalse(e.stderr.contains(TOKEN))
		assertFalse(e.message!!.contains(TOKEN))
		assertTrue(e.stderr.contains("***"))
	}

	@Test
	fun `a blank token refuses network commands without starting git`() {
		val blank = GitRunner(GitProperties(token = "", root = tmp.toString()))

		assertThrows<GitNotConfiguredException> {
			blank.run(tmp, listOf("init", "should-not-exist"), network = true)
		}
		assertFalse(Files.exists(tmp.resolve("should-not-exist")))
		assertThrows<GitNotConfiguredException> { blank.requireToken() }
	}

	@Test
	fun `a clone leaves no token in its config`() {
		val remote = bareRemote(tmp)
		val dir = tmp.resolve("clone")
		runner.runOrThrow(null, listOf("clone", fileUrl(remote), dir.toString()), network = true)

		val config = Files.readString(dir.resolve(".git/config"))
		assertFalse(config.contains(TOKEN))
		assertFalse(config.contains(base64))
		assertFalse(config.contains("extraheader"))
		assertEquals(fileUrl(remote), runner.runOrThrow(dir, listOf("remote", "get-url", "origin")).trim())
	}

	@Test
	fun `a command past its timeout is killed`() {
		val started = System.nanoTime()

		val e = assertThrows<GitTimeoutException> {
			runner.run(tmp, listOf("-c", "alias.wait=!sleep 30", "wait"), timeout = Duration.ofMillis(300))
		}

		assertEquals(Duration.ofMillis(300), e.timeout)
		assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
	}

	@Test
	fun `timeouts in minutes are described in minutes`() {
		assertEquals("git didn't finish within 2 minutes.", GitTimeoutException(Duration.ofMinutes(2)).message)
		assertEquals("git didn't finish within 30 seconds.", GitTimeoutException(Duration.ofSeconds(30)).message)
	}

	@Test
	fun `refExists finds local and remote branches`() {
		val dir = clone()

		assertTrue(runner.refExists(dir, "refs/heads/main"))
		assertTrue(runner.refExists(dir, "refs/remotes/origin/main"))
		assertFalse(runner.refExists(dir, "refs/heads/nope"))
	}

	@Test
	fun `isValidBranchName follows git's rules as written`() {
		listOf("main", "feat/003-x/02-api-y").forEach { assertTrue(runner.isValidBranchName(it), it) }
		listOf("", "-x", "a b", "bad..name", "@{-1}", "x.lock", "x/").forEach {
			assertFalse(runner.isValidBranchName(it), it)
		}
	}

	@Test
	fun `GitFailure classifies real stderr`() {
		assertEquals(GitFailure.NOT_FOUND, GitFailure.of("remote: Repository not found.\nfatal: repository 'https://github.com/acme/x.git/' not found"))
		assertEquals(GitFailure.NOT_FOUND, GitFailure.of("fatal: repository 'https://github.com/acme/x.git/' not found"))
		assertEquals(GitFailure.AUTH, GitFailure.of("fatal: Authentication failed for 'https://github.com/acme/x.git/'"))
		assertEquals(GitFailure.AUTH, GitFailure.of("fatal: could not read Username for 'https://github.com': terminal prompts disabled"))
		assertEquals(GitFailure.UNREACHABLE, GitFailure.of("fatal: unable to access 'https://github.com/acme/x.git/': Could not resolve host: github.com"))
		assertEquals(GitFailure.REJECTED, GitFailure.of(" ! [rejected]        x -> x (non-fast-forward)\nerror: failed to push some refs"))
		assertEquals(GitFailure.STALE_LEASE, GitFailure.of(" ! [rejected]        x -> x (stale info)\nerror: failed to push some refs"))
		assertEquals(GitFailure.OTHER, GitFailure.of(" ! [remote rejected] x -> x (protected branch hook declined)"))
		assertEquals(GitFailure.OTHER, GitFailure.of("fatal: something else"))
	}

	companion object {
		const val TOKEN = "ghp_testTOKEN123"
		val base64: String = Base64.getEncoder().encodeToString("x-access-token:$TOKEN".toByteArray())
	}
}
