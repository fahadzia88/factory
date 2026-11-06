package com.toptier.custom.checkout

import com.toptier.custom.checkout.CheckoutTestConfig.Companion.ROOT
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.GitFixtures.bareRemote
import com.toptier.custom.checkout.GitFixtures.commit
import com.toptier.custom.checkout.GitFixtures.fileUrl
import com.toptier.custom.checkout.GitFixtures.git
import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckout
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.checkout.exception.CheckoutNotFoundException
import com.toptier.custom.checkout.exception.CheckoutProjectArchivedException
import com.toptier.custom.checkout.service.CheckoutArchiver
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.CheckoutPaths
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeInspector
import com.toptier.custom.checkout.service.WorktreeService
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.support.MutableClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.FileSystemUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Archive deleting the checkout, unsaved work, archived refusals and the lookup by URL, with archive committing for real. */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
	ProjectService::class, CheckoutService::class, WorktreeService::class, WorktreeInspector::class, CheckoutArchiver::class,
	CheckoutLocks::class, GitRunner::class, CheckoutPaths::class, CheckoutTestConfig::class,
)
class CheckoutArchiveTests {

	@Autowired
	lateinit var projectService: ProjectService

	@Autowired
	lateinit var checkoutService: CheckoutService

	@Autowired
	lateinit var worktreeService: WorktreeService

	@Autowired
	lateinit var projectRepository: ProjectRepository

	@Autowired
	lateinit var checkouts: RepositoryCheckoutRepository

	@Autowired
	lateinit var worktrees: RepositoryWorktreeRepository

	@Autowired
	lateinit var clock: MutableClock

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	@TempDir
	lateinit var tmp: Path

	lateinit var remote: Path

	@BeforeEach
	fun setUp() {
		clock.now = START
		remote = bareRemote(tmp)
	}

	@AfterEach
	fun cleanUp() {
		worktrees.deleteAll()
		checkouts.deleteAll()
		projectRepository.deleteAll()
		ROOT.listDirectoryEntries().forEach { FileSystemUtils.deleteRecursively(it) }
	}

	@Test
	fun `archive deletes a ready checkout's folders and rows`() {
		val project = readyProject()
		val tree = worktreeService.add(project.id!!, "feat/clean", null)
		worktreeService.push(project.id!!, tree.worktree.id!!, force = false)

		projectService.archive(project.id!!)

		assertGone(project)
	}

	@Test
	fun `archive deletes unsaved work too`() {
		val project = readyProject()
		val dir = Path.of(worktreeService.add(project.id!!, "feat/wip", null).worktree.path)
		commit(dir, "wip.txt", "wip\n", "WIP")
		Files.writeString(dir.resolve("draft.txt"), "draft\n")

		projectService.archive(project.id!!)

		assertGone(project)
	}

	@Test
	fun `archive without a checkout changes nothing on disk`() {
		val project = project(fileUrl(remote))

		val archived = projectService.archive(project.id!!)

		assertTrue(archived.archived)
		assertEquals(emptyList(), ROOT.listDirectoryEntries())
	}

	@Test
	fun `archive leaves a cloning checkout's folder to its clone, which deletes it`() {
		val project = project(fileUrl(remote))
		val row = checkouts.saveAndFlush(RepositoryCheckout().apply {
			projectId = project.id!!
			path = ROOT.resolve(project.id.toString()).toString()
			state = CheckoutState.CLONING
			createdAt = START
		})
		val dir = Files.createDirectories(ROOT.resolve(project.id.toString()))

		projectService.archive(project.id!!)

		assertNull(checkouts.findByProjectId(project.id!!))
		assertTrue(Files.exists(dir))

		checkoutService.clone(row.id!!, project.id!!, fileUrl(remote))

		assertFalse(Files.exists(dir))
		assertNull(checkouts.findByProjectId(project.id!!))
	}

	@Test
	fun `restore after archive doesn't bring the checkout back`() {
		val project = readyProject()
		projectService.archive(project.id!!)

		projectService.restore(project.id!!)

		assertThrows<CheckoutNotFoundException> { checkoutService.get(project.id!!) }
		assertEquals(emptyList(), ROOT.listDirectoryEntries())
	}

	@Test
	fun `unsaved lists the worktrees with work, main first, in order`() {
		val project = readyProject()
		val main = checkoutService.get(project.id!!).worktrees.single().worktree
		Files.writeString(Path.of(main.path).resolve("notes.txt"), "notes\n")
		val dirty = worktreeService.add(project.id!!, "feat/dirty", null).worktree
		git(Path.of(dirty.path), "mv", "README.md", "RENAMED.md")
		val ahead = worktreeService.add(project.id!!, "feat/ahead", null).worktree
		val sha = commit(Path.of(ahead.path), "ahead.txt", "ahead\n", "WIP")
		worktreeService.add(project.id!!, "feat/clean", null)

		val unsaved = checkoutService.unsaved(project.id!!)

		assertEquals(listOf(main.id, dirty.id, ahead.id), unsaved.map { it.worktreeId })
		assertEquals(listOf("main", "feat/dirty", "feat/ahead"), unsaved.map { it.branch })
		assertEquals(listOf("notes.txt"), unsaved[0].changedFiles)
		assertEquals(listOf("RENAMED.md"), unsaved[1].changedFiles)
		assertEquals(emptyList(), unsaved[1].unpushedCommits)
		assertEquals(emptyList(), unsaved[2].changedFiles)
		assertEquals(listOf(sha to "WIP"), unsaved[2].unpushedCommits.map { it.sha to it.subject })
	}

	@Test
	fun `unsaved is empty without a checkout and unknown projects aren't found`() {
		val project = project(fileUrl(remote))

		assertEquals(emptyList(), checkoutService.unsaved(project.id!!))
		assertThrows<ProjectNotFoundException> { checkoutService.unsaved(999_999) }
	}

	@Test
	fun `an archived project's checkout can't be started, fetched or changed`() {
		val project = readyProject()
		val main = checkoutService.get(project.id!!).worktrees.single().worktree
		val tree = worktreeService.add(project.id!!, "feat/x", null).worktree
		projectRepository.saveAndFlush(project.apply { archived = true })

		assertThrows<CheckoutProjectArchivedException> { checkoutService.start(project.id!!) }
		assertThrows<CheckoutProjectArchivedException> { checkoutService.fetch(project.id!!) }
		assertThrows<CheckoutProjectArchivedException> { worktreeService.add(project.id!!, "feat/y", null) }
		assertThrows<CheckoutProjectArchivedException> { worktreeService.remove(project.id!!, tree.id!!, force = true) }
		assertThrows<CheckoutProjectArchivedException> { worktreeService.push(project.id!!, tree.id!!, force = false) }
		assertEquals(listOf(main.id, tree.id), worktrees.findByCheckoutIdOrderByMainDescCreatedAtAscIdAsc(checkouts.findByProjectId(project.id!!)!!.id!!).map { it.id })
	}

	@Test
	fun `findByRepositoryUrl matches the URL exactly`() {
		val project = readyProject()
		val other = project(fileUrl(tmp.resolve("other.git")))

		assertEquals(project.id, checkoutService.findByRepositoryUrl(fileUrl(remote)).checkout.projectId)
		assertThrows<ProjectNotFoundException> { checkoutService.findByRepositoryUrl(fileUrl(remote) + "/") }
		assertThrows<CheckoutNotFoundException> { checkoutService.findByRepositoryUrl(other.repositoryUrl) }
	}

	/** Both folders and both tables' rows of [project]'s checkout are gone. */
	private fun assertGone(project: Project) {
		assertFalse(Files.exists(ROOT.resolve(project.id.toString())))
		assertFalse(Files.exists(ROOT.resolve("${project.id}.worktrees")))
		assertNull(checkouts.findByProjectId(project.id!!))
		assertEquals(0, worktrees.count())
		assertTrue(projectRepository.findById(project.id!!).get().archived)
	}

	/** A project on [remote] with a `READY` checkout. */
	private fun readyProject(): Project {
		val project = project(fileUrl(remote))
		val view = checkoutService.start(project.id!!)
		assertEquals(CheckoutState.READY, checkouts.findById(view.checkout.id!!).get().state)
		return project
	}

	private fun project(url: String) = projectRepository.saveAndFlush(Project().apply {
		name = "Project ${url.hashCode()}"
		nameKey = name.lowercase()
		repositoryUrl = url
		createdAt = START
		updatedAt = START
	})
}
