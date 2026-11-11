package com.toptier.custom.feature.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** A task folder name, `NN-<layer>-<slug>`. */
data class TaskDir(val name: String, val number: Int, val layer: String, val slug: String) {
	companion object {
		private val PATTERN = Regex("^(\\d{2})-(api|app|agent|infra)-([a-z0-9]+(-[a-z0-9]+)*)$")

		/** The parsed folder name, or null when it doesn't match. */
		fun parse(name: String?): TaskDir? {
			val match = name?.let { PATTERN.matchEntire(it) } ?: return null
			val (number, layer, slug) = match.destructured
			return TaskDir(name, number.toInt(), layer, slug)
		}
	}
}

/** A task spec in the feature folder; [status] is its frontmatter `status`, null without frontmatter. */
data class TaskSpec(val dir: String, val number: Int, val layer: String, val slug: String, val specPath: String, val status: String?)

/** Reads a feature's task specs from its docs worktree. */
object TaskSpecs {

	/** Every `<folder>/tasks/<dir>/spec.md` whose folder name parses, in number order. */
	fun scan(worktree: Path, folder: String): List<TaskSpec> {
		val tasks = worktree.resolve("$folder/tasks")
		if (!tasks.isDirectory()) return emptyList()
		return tasks.listDirectoryEntries()
			.filter { it.isDirectory() && Files.isRegularFile(it.resolve("spec.md")) }
			.mapNotNull { dir ->
				val parsed = TaskDir.parse(dir.name) ?: return@mapNotNull null
				TaskSpec(parsed.name, parsed.number, parsed.layer, parsed.slug, "$folder/tasks/${parsed.name}/spec.md", status(dir.resolve("spec.md")))
			}
			.sortedBy { it.number }
	}

	/** The `status:` line of the file's leading `---` frontmatter, or null. */
	fun status(spec: Path): String? {
		val lines = Files.readAllLines(spec)
		if (lines.firstOrNull()?.trim() != "---") return null
		for (line in lines.drop(1)) {
			if (line.trim() == "---") return null
			if (line.startsWith("status:")) return line.removePrefix("status:").trim().ifEmpty { null }
		}
		return null
	}
}
