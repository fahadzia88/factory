package com.toptier.custom.feature

import com.toptier.custom.feature.service.TaskDir
import com.toptier.custom.feature.service.TaskSpecs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskSpecsTests {

	@TempDir
	lateinit var worktree: Path

	@Test
	fun `task folder names parse`() {
		assertEquals(TaskDir("02-api-task-start", 2, "api", "task-start"), TaskDir.parse("02-api-task-start"))
		assertEquals("infra", TaskDir.parse("15-infra-implementation-skill")?.layer)
		assertNull(TaskDir.parse("02-db-x"))
		assertNull(TaskDir.parse("2-api-x"))
		assertNull(TaskDir.parse("02-api-Bad_Slug"))
		assertNull(TaskDir.parse("02-api-x-"))
		assertNull(TaskDir.parse(null))
	}

	@Test
	fun `scan reads each spec's frontmatter status in number order`() {
		spec("02-app-b", "---\nfeature: 006-x\nstatus: draft\n---\n# B\n")
		spec("01-api-a", "---\nstatus: approved\napproved: 2026-11-10\n---\n# A\n")
		spec("03-agent-c", "# No frontmatter\nstatus: approved\n")
		Files.createDirectories(worktree.resolve("feature/006-x/tasks/notes"))

		val specs = TaskSpecs.scan(worktree, "feature/006-x")

		assertEquals(listOf("01-api-a", "02-app-b", "03-agent-c"), specs.map { it.dir })
		assertEquals(listOf("approved", "draft", null), specs.map { it.status })
		assertEquals("feature/006-x/tasks/01-api-a/spec.md", specs[0].specPath)
	}

	@Test
	fun `no tasks folder means no specs`() {
		assertEquals(emptyList(), TaskSpecs.scan(worktree, "feature/006-x"))
	}

	private fun spec(dir: String, text: String) {
		val path = worktree.resolve("feature/006-x/tasks/$dir/spec.md")
		Files.createDirectories(path.parent)
		Files.writeString(path, text)
	}
}
