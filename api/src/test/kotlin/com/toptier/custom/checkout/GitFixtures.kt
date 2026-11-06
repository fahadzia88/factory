package com.toptier.custom.checkout

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/** Local git repositories for tests: bare remotes in a temp dir, reached through `file://` URLs. */
object GitFixtures {

	/** Runs git directly (not through `GitRunner`) in [dir] with a test identity; fails the test on a non-zero exit. */
	fun git(dir: Path, vararg args: String): String {
		val command = listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.com") + args
		val process = ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start()
		process.outputStream.close()
		val output = process.inputStream.bufferedReader().readText()
		if (!process.waitFor(30, TimeUnit.SECONDS)) fail("git ${args.joinToString(" ")} timed out")
		if (process.exitValue() != 0) fail("git ${args.joinToString(" ")} failed (${process.exitValue()}): $output")
		return output.trim()
	}

	/** A bare repository `<parent>/<name>` with one commit (`README.md`) on [branch]. */
	fun bareRemote(parent: Path, name: String = "remote.git", branch: String = "main"): Path {
		val remote = parent.resolve(name)
		Files.createDirectories(remote)
		git(remote, "init", "--bare", "--initial-branch=$branch")
		val seed = Files.createTempDirectory(parent, "seed")
		git(seed, "clone", fileUrl(remote), ".")
		git(seed, "checkout", "-b", branch)
		commit(seed, "README.md", "# Test\n", "Initial commit")
		git(seed, "push", "origin", branch)
		return remote
	}

	/** Writes [file] in [dir], commits it with [message] and returns the short SHA. */
	fun commit(dir: Path, file: String, content: String, message: String): String {
		val target = dir.resolve(file)
		Files.createDirectories(target.parent)
		Files.writeString(target, content)
		git(dir, "add", file)
		git(dir, "commit", "-m", message)
		return git(dir, "rev-parse", "--short", "HEAD")
	}

	/** The `file://` URL of [path]. */
	fun fileUrl(path: Path) = "file://${path.toAbsolutePath()}"
}
