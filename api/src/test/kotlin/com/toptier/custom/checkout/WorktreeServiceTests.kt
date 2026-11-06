package com.toptier.custom.checkout

import com.toptier.custom.checkout.CheckoutTestConfig.Companion.ROOT
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.GitFixtures.bareRemote
import com.toptier.custom.checkout.GitFixtures.commit
import com.toptier.custom.checkout.GitFixtures.fileUrl
import com.toptier.custom.checkout.GitFixtures.git
import com.toptier.custom.checkout.GitFixtures.scratchClone
import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.checkout.exception.BaseNotFoundException
import com.toptier.custom.checkout.exception.BranchCheckedOutException
import com.toptier.custom.checkout.exception.GitRemoteException
import com.toptier.custom.checkout.exception.InvalidBranchException
import com.toptier.custom.checkout.exception.MainWorktreeException
import com.toptier.custom.checkout.exception.WorktreeNotFoundException
import com.toptier.custom.checkout.exception.WorktreeUnsavedException
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.CheckoutPaths
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeInspector
import com.toptier.custom.checkout.service.WorktreeService
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
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
import org.springframework.util.FileSystemUtils
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@Import(
	CheckoutService::class, WorktreeService::class, WorktreeInspector::class, ProjectService::class, CheckoutLocks::class,
	GitRunner::class, CheckoutPaths::class, CheckoutTestConfig::class,
)
class WorktreeServiceTests {

	@Autowired
	lateinit var service: WorktreeService

	@Autowired
	lateinit var checkoutService: CheckoutService

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

	lateinit var project: Project

	lateinit var clone: Path

	@BeforeEach
	fun readyCheckout() {
		clock.now = START
		remote = bareRemote(tmp)
		val scratch = scratchClone(tmp, remote)
		git(scratch, "checkout", "-b", "feat/existing")
		commit(scratch, "existing.txt", "existing\n", "Add existing")
		git(scratch, "push", "origin", "feat/existing")
		project = projectRepository.saveAndFlush(Project().apply {
			name = "Project ${remote.hashCode()}"
			nameKey = name.lowercase()
			repositoryUrl = fileUrl(remote)
			createdAt = START
			updatedAt = START
		})
		val view = checkoutService.start(project.id!!)
		assertEquals(CheckoutState.READY, checkouts.findById(view.checkout.id!!).get().state)
		clone = Path.of(view.checkout.path)
		clock.advance(Duration.ofMinutes(1))
	}

	@AfterEach
	fun emptyRoot() {
		ROOT.listDirectoryEntries().forEach { FileSystemUtils.deleteRecursively(it) }
	}

	@Test
	fun `a remote branch gets its own folder tracking origin`() {
		val view = service.add(project.id!!, "feat/existing", null)

		val dir = ROOT.resolve("${project.id}.worktrees").resolve("feat__existing")
		assertEquals(dir.toString(), view.worktree.path)
		assertNull(view.worktree.baseBranch)
		assertFalse(view.worktree.main)
		assertEquals("origin/feat/existing", git(dir, "rev-parse", "--abbrev-ref", "feat/existing@{upstream}"))
		assertTrue(Files.exists(dir.resolve("existing.txt")))
		val listed = checkoutService.get(project.id!!).worktrees
		assertEquals(listOf("main", "feat/existing"), listed.map { it.worktree.branch })
		assertEquals("Add existing", listed[1].head!!.subject)
		assertEquals(git(dir, "rev-parse", "--short", "HEAD"), listed[1].head!!.sha)
		assertFalse(listed[1].missing)
		assertNotNull(listed[0].head)
	}

	@Test
	fun `add fetches first, so a just-pushed commit is the head`() {
		val scratch = scratchClone(tmp, remote)
		git(scratch, "checkout", "feat/existing")
		val sha = commit(scratch, "late.txt", "late\n", "Late commit")
		git(scratch, "push", "origin", "feat/existing")

		val view = service.add(project.id!!, "feat/existing", null)

		assertEquals(sha, view.head!!.sha)
		assertEquals("Late commit", view.head!!.subject)
	}

	@Test
	fun `a new branch starts from origin main, or from the base given`() {
		val fromMain = service.add(project.id!!, "feat/new", null)
		assertEquals("main", fromMain.worktree.baseBranch)
		val mainDir = Path.of(fromMain.worktree.path)
		assertEquals(git(clone, "rev-parse", "origin/main"), git(mainDir, "rev-parse", "HEAD"))
		assertNoUpstream(mainDir, "feat/new")

		val fromBase = service.add(project.id!!, "feat/other", "feat/existing")
		assertEquals("feat/existing", fromBase.worktree.baseBranch)
		val baseDir = Path.of(fromBase.worktree.path)
		assertEquals(git(clone, "rev-parse", "origin/feat/existing"), git(baseDir, "rev-parse", "HEAD"))
		assertNoUpstream(baseDir, "feat/other")
	}

	@Test
	fun `refusals leave nothing on disk or in the table`() {
		assertThrows<InvalidBranchException> { service.add(project.id!!, "bad..name", null) }
		assertThrows<InvalidBranchException> { service.add(project.id!!, null, null) }
		assertThrows<BaseNotFoundException> { service.add(project.id!!, "feat/a", "nope") }
		assertThrows<BranchCheckedOutException> { service.add(project.id!!, "main", null) }
		assertEquals(1, worktrees.count())
		assertFalse(Files.exists(ROOT.resolve("${project.id}.worktrees").resolve("feat__a")))

		service.add(project.id!!, "a/b", null)
		assertThrows<BranchCheckedOutException> { service.add(project.id!!, "a/b", null) }
		val collision = assertThrows<BranchCheckedOutException> { service.add(project.id!!, "a__b", null) }
		assertEquals("a__b would use the folder a__b, which another worktree has.", collision.message)
		assertEquals(2, worktrees.count())
		assertEquals("", git(clone, "branch", "--list", "a__b"))
	}

	@Test
	fun `worktrees don't share files`() {
		val one = Path.of(service.add(project.id!!, "feat/one", null).worktree.path)
		val two = Path.of(service.add(project.id!!, "feat/two", null).worktree.path)

		Files.writeString(one.resolve("only-here.txt"), "one\n")

		assertFalse(Files.exists(two.resolve("only-here.txt")))
		assertFalse(Files.exists(clone.resolve("only-here.txt")))
	}

	@Test
	fun `removing a clean, pushed worktree deletes its folder, row and branch`() {
		val view = service.add(project.id!!, "feat/existing", null)
		val dir = Path.of(view.worktree.path)

		service.remove(project.id!!, view.worktree.id!!, force = false)

		assertFalse(Files.exists(dir))
		assertFalse(worktrees.existsById(view.worktree.id!!))
		assertEquals("", git(clone, "branch", "--list", "feat/existing"))
	}

	@Test
	fun `unsaved work blocks removal unless forced, and force keeps the branch`() {
		val view = service.add(project.id!!, "feat/wip", null)
		val dir = Path.of(view.worktree.path)
		val sha = commit(dir, "wip.txt", "wip\n", "WIP")
		Files.writeString(dir.resolve("scratch.txt"), "untracked\n")

		val ex = assertThrows<WorktreeUnsavedException> { service.remove(project.id!!, view.worktree.id!!, force = false) }

		assertEquals(view.worktree.id, ex.unsaved.worktreeId)
		assertEquals("feat/wip", ex.unsaved.branch)
		assertEquals(listOf("scratch.txt"), ex.unsaved.changedFiles)
		assertEquals(listOf(sha), ex.unsaved.unpushedCommits.map { it.sha })
		assertEquals("WIP", ex.unsaved.unpushedCommits[0].subject)
		assertTrue(Files.exists(dir.resolve("scratch.txt")))

		service.remove(project.id!!, view.worktree.id!!, force = true)

		assertFalse(Files.exists(dir))
		assertFalse(worktrees.existsById(view.worktree.id!!))
		assertEquals(sha, git(clone, "rev-parse", "--short", "feat/wip"))
	}

	@Test
	fun `a renamed file is listed under its new path`() {
		val view = service.add(project.id!!, "feat/existing", null)
		val dir = Path.of(view.worktree.path)
		git(dir, "mv", "existing.txt", "renamed.txt")

		val ex = assertThrows<WorktreeUnsavedException> { service.remove(project.id!!, view.worktree.id!!, force = false) }

		assertEquals(listOf("renamed.txt"), ex.unsaved.changedFiles)
		assertEquals(emptyList(), ex.unsaved.unpushedCommits)
	}

	@Test
	fun `the main worktree can't be removed`() {
		val main = checkoutService.get(project.id!!).worktrees.single()
		assertThrows<MainWorktreeException> { service.remove(project.id!!, main.worktree.id!!, force = true) }
		assertTrue(Files.exists(clone.resolve(".git")))
	}

	@Test
	fun `a folder deleted by hand shows as missing and is pruned on remove`() {
		val view = service.add(project.id!!, "feat/gone", null)
		FileSystemUtils.deleteRecursively(Path.of(view.worktree.path))

		val listed = checkoutService.get(project.id!!).worktrees[1]
		assertTrue(listed.missing)
		assertNull(listed.head)

		service.remove(project.id!!, view.worktree.id!!, force = false)

		assertFalse(worktrees.existsById(view.worktree.id!!))
		assertFalse(git(clone, "worktree", "list").contains("feat__gone"))
	}

	@Test
	fun `adding with the remote deleted is a remote error`() {
		FileSystemUtils.deleteRecursively(remote)

		assertThrows<GitRemoteException> { service.add(project.id!!, "feat/new", null) }

		assertEquals(1, worktrees.count())
	}

	@Test
	fun `unknown worktrees and other projects' worktrees aren't found`() {
		assertThrows<WorktreeNotFoundException> { service.remove(project.id!!, 9999, force = false) }

		val other = projectRepository.saveAndFlush(Project().apply {
			name = "Other"
			nameKey = "other"
			repositoryUrl = fileUrl(bareRemote(tmp, "other.git"))
			createdAt = START
			updatedAt = START
		})
		checkoutService.start(other.id!!)
		val main = checkoutService.get(project.id!!).worktrees.single()
		assertThrows<WorktreeNotFoundException> { service.remove(other.id!!, main.worktree.id!!, force = false) }
	}

	private fun assertNoUpstream(dir: Path, branch: String) {
		val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "$branch@{upstream}").directory(dir.toFile()).start()
		process.waitFor()
		assertTrue(process.exitValue() != 0, "$branch should have no upstream")
	}
}
