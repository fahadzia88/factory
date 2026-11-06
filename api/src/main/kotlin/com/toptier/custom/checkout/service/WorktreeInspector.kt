package com.toptier.custom.checkout.service

import com.toptier.custom.checkout.domain.RepositoryWorktree
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/** Reads a worktree's state from disk with local git only: whether it's missing, its head, and its unsaved work. */
@Component
class WorktreeInspector(private val runner: GitRunner) {

	/** True when the worktree's folder, or its `.git`, isn't on disk. */
	fun missing(path: Path): Boolean = !Files.exists(path.resolve(".git"))

	/** The last commit checked out in [path]; null when the worktree is missing or git can't read it. */
	fun head(path: Path): HeadCommit? {
		if (missing(path)) return null
		val result = runner.run(path, listOf("log", "-1", "--format=%h%x00%s"))
		if (result.exitCode != 0) return null
		return commits(result.stdout).firstOrNull()
	}

	/**
	 * The worktree's changed and untracked files and its commits on no `origin` branch, at most
	 * [LIMIT] of each. A failed git command throws `GitCommandException` rather than reading as clean.
	 */
	fun unsaved(worktree: RepositoryWorktree): UnsavedWork {
		val path = Path.of(worktree.path)
		val status = runner.runOrThrow(path, listOf("status", "--porcelain=v1", "-z"))
		val log = runner.runOrThrow(
			path,
			listOf("log", "--format=%h%x00%s", "-n", LIMIT.toString(), "refs/heads/${worktree.branch}", "--not", "--remotes=origin"),
		)
		return UnsavedWork(
			worktreeId = requireNotNull(worktree.id) { "Worktree has not been saved" },
			branch = worktree.branch,
			changedFiles = changedFiles(status),
			unpushedCommits = commits(log),
		)
	}

	/** Paths from `status --porcelain=v1 -z` output; a rename or copy lists its new path and skips the original. */
	private fun changedFiles(output: String): List<String> {
		val fields = output.split('\u0000')
		val files = mutableListOf<String>()
		var i = 0
		while (i < fields.size && files.size < LIMIT) {
			val entry = fields[i]
			i++
			if (entry.length < 4) continue
			files += entry.substring(3)
			if (entry[0] == 'R' || entry[0] == 'C') i++
		}
		return files
	}

	/** One commit per `%h%x00%s` line. */
	private fun commits(output: String): List<HeadCommit> = output.lineSequence()
		.filter { it.isNotBlank() }
		.map {
			val parts = it.split('\u0000', limit = 2)
			HeadCommit(parts[0], parts.getOrElse(1) { "" })
		}
		.toList()

	private companion object {
		const val LIMIT = 50
	}
}
