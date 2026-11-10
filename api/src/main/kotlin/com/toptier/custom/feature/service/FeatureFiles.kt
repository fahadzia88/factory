package com.toptier.custom.feature.service

import com.toptier.custom.feature.exception.FeatureFileNotFoundException
import com.toptier.custom.feature.exception.FileTooLargeException
import com.toptier.custom.feature.exception.InvalidFilePathException
import com.toptier.custom.feature.exception.UnsupportedFileTypeException
import com.toptier.custom.feature.exception.WorktreeMissingException
import org.springframework.stereotype.Service
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Lists and reads the files of a feature's folder in its docs-branch worktree, straight from disk so
 * uncommitted edits show. The path to read comes from a request, so every check that keeps it inside
 * the folder (lexical and after following symlinks) is done here, before anything is read; `.git` is
 * never listed or served.
 */
@Service
class FeatureFiles(private val featureService: FeatureService) {

	/** The folder tree of the feature; empty when the folder doesn't exist yet. Throws [WorktreeMissingException] without a worktree. */
	fun tree(projectId: Long, featureId: Long): FeatureFileTree {
		val view = featureService.get(projectId, featureId)
		val root = resolveRoot(view)
		val entries = root?.let { list(it, it, "", 1, Counter()) } ?: emptyList()
		return FeatureFileTree(view.feature.folder, entries)
	}

	/**
	 * The markdown file at [path], relative to the feature's folder. Throws [InvalidFilePathException] for a path that
	 * isn't inside the folder, [FeatureFileNotFoundException], [UnsupportedFileTypeException] for a non-`.md` file,
	 * [FileTooLargeException] over [MAX_FILE_SIZE] bytes and [WorktreeMissingException] without a worktree.
	 */
	fun read(projectId: Long, featureId: Long, path: String): FeatureFileContent {
		checkPath(path)
		val view = featureService.get(projectId, featureId)
		val root = resolveRoot(view) ?: throw FeatureFileNotFoundException()
		val target = root.resolve(path)
		if (!Files.exists(target)) throw FeatureFileNotFoundException()
		val real = target.toRealPath()
		if (!real.startsWith(root)) throw InvalidFilePathException()
		if (!Files.isRegularFile(real)) throw FeatureFileNotFoundException()
		if (!path.substringAfterLast('/').endsWith(".md", ignoreCase = true)) throw UnsupportedFileTypeException()
		val size = Files.size(real)
		if (size > MAX_FILE_SIZE) throw FileTooLargeException()
		val content = String(Files.readAllBytes(real), Charsets.UTF_8)
		return FeatureFileContent(path, content, size, Files.getLastModifiedTime(real).toInstant())
	}

	/** Rejects [path] without touching the filesystem when it's blank, absolute, has `\` or NUL, or a `..`, `.git` or empty segment. */
	private fun checkPath(path: String) {
		if (path.isBlank() || path.startsWith("/") || path.contains('\\') || path.contains('\u0000')) {
			throw InvalidFilePathException()
		}
		if (path.split('/').any { it.isEmpty() || it == ".." || it.equals(".git", ignoreCase = true) }) {
			throw InvalidFilePathException()
		}
	}

	/**
	 * The real path of the feature's folder, or null when it doesn't exist yet or is a symlink out of the worktree.
	 * Throws [WorktreeMissingException] when the feature has no worktree on disk.
	 */
	private fun resolveRoot(view: FeatureView): Path? {
		val worktree = view.worktree
		if (worktree == null || worktree.missing) throw WorktreeMissingException(view.feature.branch)
		val worktreeRoot = Path.of(worktree.path)
		val folder = worktreeRoot.resolve(view.feature.folder)
		if (!Files.isDirectory(folder)) return null
		val realRoot = folder.toRealPath()
		return if (realRoot.startsWith(worktreeRoot.toRealPath())) realRoot else null
	}

	/** The sorted entries of [dir] at depth [level], skipping `.git`, broken symlinks and links out of [realRoot]. */
	private fun list(realRoot: Path, dir: Path, relPrefix: String, level: Int, counter: Counter): List<FeatureFileNode> {
		val found = Files.newDirectoryStream(dir).use { stream ->
			stream.mapNotNull { entry ->
				val name = entry.fileName.toString()
				if (name == ".git") return@mapNotNull null
				val real = try {
					entry.toRealPath()
				} catch (_: IOException) {
					return@mapNotNull null
				}
				if (!real.startsWith(realRoot)) return@mapNotNull null
				when {
					Files.isDirectory(real) -> Found(name, real, true)
					Files.isRegularFile(real) -> Found(name, real, false)
					else -> null
				}
			}
		}.sortedWith(compareBy<Found>({ !it.directory }, { it.name.lowercase() }))
		val nodes = mutableListOf<FeatureFileNode>()
		for (entry in found) {
			if (counter.count >= MAX_ENTRIES) break
			counter.count++
			val path = relPrefix + entry.name
			nodes += if (entry.directory) {
				val children = if (level < MAX_DEPTH) list(realRoot, entry.real, "$path/", level + 1, counter) else emptyList()
				FeatureFileNode(entry.name, path, directory = true, markdown = false, children = children)
			} else {
				FeatureFileNode(entry.name, path, directory = false, markdown = entry.name.endsWith(".md", ignoreCase = true), children = emptyList())
			}
		}
		return nodes
	}

	/** A directory entry kept for the tree, with its real path. */
	private data class Found(val name: String, val real: Path, val directory: Boolean)

	/** Nodes added so far across the whole tree. */
	private class Counter(var count: Int = 0)

	companion object {
		/** Files larger than this many bytes aren't read. */
		const val MAX_FILE_SIZE = 200L * 1024

		/** The tree holds at most this many nodes. */
		const val MAX_ENTRIES = 500

		/** The tree goes at most this many levels deep. */
		const val MAX_DEPTH = 8
	}
}
