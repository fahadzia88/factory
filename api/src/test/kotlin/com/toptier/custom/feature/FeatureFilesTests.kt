package com.toptier.custom.feature

import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.exception.FeatureFileNotFoundException
import com.toptier.custom.feature.exception.FileTooLargeException
import com.toptier.custom.feature.exception.InvalidFilePathException
import com.toptier.custom.feature.exception.UnsupportedFileTypeException
import com.toptier.custom.feature.exception.WorktreeMissingException
import com.toptier.custom.feature.service.FeatureFileNode
import com.toptier.custom.feature.service.FeatureFiles
import com.toptier.custom.feature.service.FeatureService
import com.toptier.custom.feature.service.FeatureView
import com.toptier.custom.feature.service.FeatureWorktree
import com.toptier.custom.feature.service.Workflow
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [FeatureFiles] against a feature folder in a temporary worktree. */
class FeatureFilesTests {

	@TempDir
	lateinit var tempDir: Path

	private lateinit var worktree: Path
	private lateinit var folder: Path
	private val featureService: FeatureService = mock(FeatureService::class.java)
	private val files = FeatureFiles(featureService)

	@BeforeEach
	fun setUp() {
		worktree = Files.createDirectories(tempDir.resolve("worktree"))
		folder = worktree.resolve(FOLDER)
		givenWorktree(FeatureWorktree(1, worktree.toString(), false))
	}

	@Test
	fun `tree lists folders first then files by name with paths and markdown flags`() {
		write("requirements.md", "# Requirements")
		write("tasks/01-x/spec.md", "# Spec")
		write("Diagram.png", "png")
		write("b.md", "b")

		val tree = files.tree(PROJECT_ID, FEATURE_ID)

		assertEquals(FOLDER, tree.root)
		assertEquals(
			listOf(
				dir(
					"tasks", "tasks",
					dir("01-x", "tasks/01-x", file("spec.md", "tasks/01-x/spec.md", true)),
				),
				file("b.md", "b.md", true),
				file("Diagram.png", "Diagram.png", false),
				file("requirements.md", "requirements.md", true),
			),
			tree.entries,
		)
	}

	@Test
	fun `tree never lists git entries or symlinks out of the root`() {
		write(".git/HEAD", "ref")
		write("sub/.git", "gitdir: x")
		val outside = Files.writeString(tempDir.resolve("secret.md"), "secret")
		assumeSymlinks { Files.createSymbolicLink(folder.resolve("link.md"), outside) }
		Files.createSymbolicLink(folder.resolve("out"), tempDir)

		val tree = files.tree(PROJECT_ID, FEATURE_ID)

		assertEquals(listOf(dir("sub", "sub")), tree.entries)
	}

	@Test
	fun `a missing or empty folder gives no entries`() {
		assertEquals(emptyList(), files.tree(PROJECT_ID, FEATURE_ID).entries)

		Files.createDirectories(folder)

		assertEquals(emptyList(), files.tree(PROJECT_ID, FEATURE_ID).entries)
	}

	@Test
	fun `tree stops at eight levels`() {
		Files.createDirectories(folder.resolve((1..9).joinToString("/") { "d$it" }))

		var node = files.tree(PROJECT_ID, FEATURE_ID).entries.single()
		var depth = 1
		while (node.children.isNotEmpty()) {
			node = node.children.single()
			depth++
		}

		assertEquals(8, depth)
		assertEquals("d8", node.name)
	}

	@Test
	fun `tree stops at 500 entries`() {
		for (i in 1..30) {
			val sub = Files.createDirectories(folder.resolve("d%02d".format(i)))
			for (j in 1..20) Files.writeString(sub.resolve("f%02d.md".format(j)), "x")
		}

		val tree = files.tree(PROJECT_ID, FEATURE_ID)

		assertEquals(500, count(tree.entries))
	}

	@Test
	fun `read returns the file from disk including uncommitted edits`() {
		val file = write("requirements.md", "# Requirements")
		val modified = Instant.parse("2026-11-01T10:00:00Z")
		Files.setLastModifiedTime(file, FileTime.from(modified))

		val first = files.read(PROJECT_ID, FEATURE_ID, "requirements.md")

		assertEquals("requirements.md", first.path)
		assertEquals("# Requirements", first.content)
		assertEquals(14L, first.size)
		assertEquals(modified, first.updatedAt)

		Files.writeString(file, "# Changed")

		assertEquals("# Changed", files.read(PROJECT_ID, FEATURE_ID, "requirements.md").content)
	}

	@Test
	fun `read returns a nested file`() {
		write("tasks/01-x/spec.md", "# Spec")

		assertEquals("# Spec", files.read(PROJECT_ID, FEATURE_ID, "tasks/01-x/spec.md").content)
	}

	@Test
	fun `read of a missing file or a folder is not found`() {
		write("tasks/01-x/spec.md", "# Spec")

		assertThrows<FeatureFileNotFoundException> { files.read(PROJECT_ID, FEATURE_ID, "nope.md") }
		assertThrows<FeatureFileNotFoundException> { files.read(PROJECT_ID, FEATURE_ID, "tasks") }
	}

	@Test
	fun `read without the feature folder is not found`() {
		assertThrows<FeatureFileNotFoundException> { files.read(PROJECT_ID, FEATURE_ID, "requirements.md") }
	}

	@Test
	fun `read serves only markdown files`() {
		write("diagram.png", "png")
		write("NOTES.MD", "notes")

		assertThrows<UnsupportedFileTypeException> { files.read(PROJECT_ID, FEATURE_ID, "diagram.png") }
		assertEquals("notes", files.read(PROJECT_ID, FEATURE_ID, "NOTES.MD").content)
	}

	@Test
	fun `read rejects files over 200 KB`() {
		write("big.md", "x".repeat(200 * 1024 + 1))
		write("limit.md", "x".repeat(200 * 1024))

		assertThrows<FileTooLargeException> { files.read(PROJECT_ID, FEATURE_ID, "big.md") }
		assertEquals(200L * 1024, files.read(PROJECT_ID, FEATURE_ID, "limit.md").size)
	}

	@Test
	fun `read rejects paths outside the folder`() {
		write("requirements.md", "# Requirements")
		write("a/x.md", "x")
		Files.writeString(worktree.resolve("feature/rules.md"), "rules")

		for (path in listOf("../rules.md", "/etc/passwd", "a/../../x.md", "a\u0000.md", ".git/config", "x/.GIT/y.md", "", "  ", "a//x.md", "a\\x.md")) {
			assertThrows<InvalidFilePathException>(path) { files.read(PROJECT_ID, FEATURE_ID, path) }
		}
	}

	@Test
	fun `read rejects symlinks that lead out of the folder`() {
		Files.createDirectories(folder)
		val outsideDir = Files.createDirectories(tempDir.resolve("outside"))
		val outside = Files.writeString(outsideDir.resolve("x.md"), "secret")
		assumeSymlinks { Files.createSymbolicLink(folder.resolve("link.md"), outside) }
		Files.createSymbolicLink(folder.resolve("out"), outsideDir)

		assertThrows<InvalidFilePathException> { files.read(PROJECT_ID, FEATURE_ID, "link.md") }
		assertThrows<InvalidFilePathException> { files.read(PROJECT_ID, FEATURE_ID, "out/x.md") }
		assertFalse(files.tree(PROJECT_ID, FEATURE_ID).toString().contains("secret"))
	}

	@Test
	fun `a symlink inside the folder is served`() {
		write("requirements.md", "# Requirements")
		assumeSymlinks { Files.createSymbolicLink(folder.resolve("alias.md"), folder.resolve("requirements.md")) }

		assertEquals("# Requirements", files.read(PROJECT_ID, FEATURE_ID, "alias.md").content)
		assertTrue(files.tree(PROJECT_ID, FEATURE_ID).entries.any { it.name == "alias.md" })
	}

	@Test
	fun `no worktree or a missing one is reported`() {
		write("requirements.md", "# Requirements")
		for (wt in listOf(null, FeatureWorktree(1, worktree.toString(), true))) {
			givenWorktree(wt)

			val treeError = assertThrows<WorktreeMissingException> { files.tree(PROJECT_ID, FEATURE_ID) }
			assertThrows<WorktreeMissingException> { files.read(PROJECT_ID, FEATURE_ID, "requirements.md") }
			assertEquals(BRANCH, treeError.branch)
		}
	}

	private fun givenWorktree(wt: FeatureWorktree?) {
		val feature = Feature().apply {
			id = FEATURE_ID
			projectId = PROJECT_ID
			folder = FOLDER
			branch = BRANCH
		}
		given(featureService.get(PROJECT_ID, FEATURE_ID)).willReturn(FeatureView(feature, Workflow(listOf("draft"), emptyList()), emptyList(), wt))
	}

	private fun write(path: String, content: String): Path {
		val file = folder.resolve(path)
		Files.createDirectories(file.parent)
		return Files.writeString(file, content)
	}

	private fun assumeSymlinks(create: () -> Unit) {
		val ok = try {
			create()
			true
		} catch (_: Exception) {
			false
		}
		assumeTrue(ok, "symlinks not supported")
	}

	private fun count(nodes: List<FeatureFileNode>): Int = nodes.sumOf { 1 + count(it.children) }

	private fun dir(name: String, path: String, vararg children: FeatureFileNode) =
		FeatureFileNode(name, path, directory = true, markdown = false, children = children.toList())

	private fun file(name: String, path: String, markdown: Boolean) =
		FeatureFileNode(name, path, directory = false, markdown = markdown, children = emptyList())

	companion object {
		const val PROJECT_ID = 7L
		const val FEATURE_ID = 3L
		const val FOLDER = "feature/005-probe"
		const val BRANCH = "feat/005-probe/00-docs"
	}
}
