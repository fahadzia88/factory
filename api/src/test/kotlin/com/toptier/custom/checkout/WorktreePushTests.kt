package com.toptier.custom.checkout

import com.toptier.custom.checkout.CheckoutTestConfig.Companion.ROOT
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.GitFixtures.bareRemote
import com.toptier.custom.checkout.GitFixtures.commit
import com.toptier.custom.checkout.GitFixtures.fileUrl
import com.toptier.custom.checkout.GitFixtures.git
import com.toptier.custom.checkout.GitFixtures.scratchClone
import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckout
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktree
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.checkout.exception.GitRemoteException
import com.toptier.custom.checkout.exception.PushDefaultBranchException
import com.toptier.custom.checkout.exception.PushRejectedException
import com.toptier.custom.checkout.exception.WorktreeMissingException
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
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pushes to a `file://` bare remote standing in for GitHub; a scratch clone plays a second contributor. */
@DataJpaTest
@Import(
	CheckoutService::class, WorktreeService::class, WorktreeInspector::class, ProjectService::class, CheckoutLocks::class,
	GitRunner::class, CheckoutPaths::class, CheckoutTestConfig::class,
)
class WorktreePushTests {

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
	fun `a new branch is pushed with its upstream set, then fetched`() {
		val (id, dir) = addWorktree("feat/new")
		val sha = commit(dir, "new.txt", "new\n", "Add new")
		val fetchedBefore = checkouts.findByProjectId(project.id!!)!!.lastFetchedAt!!
		clock.advance(Duration.ofMinutes(5))

		val result = service.push(project.id!!, id, force = false)

		assertEquals("feat/new", result.branch)
		assertEquals(sha, result.head)
		assertEquals(git(dir, "rev-parse", "HEAD"), git(remote, "rev-parse", "refs/heads/feat/new"))
		assertEquals("origin/feat/new", git(dir, "rev-parse", "--abbrev-ref", "@{u}"))
		val fetchedAfter = checkouts.findByProjectId(project.id!!)!!.lastFetchedAt!!
		assertTrue(fetchedAfter.isAfter(fetchedBefore), "lastFetchedAt should move with the fetch after the push")
	}

	@Test
	fun `the default branch is never pushed`() {
		val mainRow = checkoutService.get(project.id!!).worktrees.single { it.worktree.main }.worktree
		val remoteMain = git(remote, "rev-parse", "refs/heads/main")
		commit(clone, "local.txt", "local\n", "Local only")

		assertThrows<PushDefaultBranchException> { service.push(project.id!!, mainRow.id!!, force = true) }
		assertEquals(remoteMain, git(remote, "rev-parse", "refs/heads/main"))

		// A non-main row on the default branch, in a second checkout whose folders don't exist: the
		// refusal comes before the missing-folder check, so no git runs.
		val other = projectRepository.saveAndFlush(Project().apply {
			name = "Other"
			nameKey = "other"
			repositoryUrl = fileUrl(tmp.resolve("other.git"))
			createdAt = START
			updatedAt = START
		})
		val otherCheckout = checkouts.saveAndFlush(RepositoryCheckout().apply {
			projectId = other.id!!
			path = tmp.resolve("nowhere").toString()
			defaultBranch = "main"
			state = CheckoutState.READY
			createdAt = START
		})
		val row = worktrees.saveAndFlush(RepositoryWorktree().apply {
			checkoutId = otherCheckout.id!!
			branch = "main"
			path = tmp.resolve("nowhere.worktrees/main").toString()
			main = false
			createdAt = START
		})

		val ex = assertThrows<PushDefaultBranchException> { service.push(other.id!!, row.id!!, force = false) }
		assertEquals("main is the repository's default branch and can't be pushed from here.", ex.message)
	}

	@Test
	fun `a rebased branch is rejected without force and replaced with it`() {
		val (id, dir) = addWorktree("feat/rebase")
		commit(dir, "r.txt", "one\n", "First")
		service.push(project.id!!, id, force = false)
		val pushed = git(remote, "rev-parse", "refs/heads/feat/rebase")
		git(dir, "commit", "--amend", "-m", "First, reworded")

		val ex = assertThrows<PushRejectedException> { service.push(project.id!!, id, force = false) }
		assertFalse(ex.lease)
		assertEquals(pushed, git(remote, "rev-parse", "refs/heads/feat/rebase"))

		val result = service.push(project.id!!, id, force = true)

		assertEquals(git(dir, "rev-parse", "HEAD"), git(remote, "rev-parse", "refs/heads/feat/rebase"))
		assertEquals(git(dir, "rev-parse", "--short", "HEAD"), result.head)
	}

	@Test
	fun `a forced push is refused when the branch moved on GitHub since the last fetch`() {
		val (id, dir) = addWorktree("feat/lease")
		commit(dir, "l.txt", "one\n", "First")
		service.push(project.id!!, id, force = false)
		val scratch = scratchClone(tmp, remote)
		git(scratch, "checkout", "feat/lease")
		commit(scratch, "theirs.txt", "theirs\n", "Their commit")
		git(scratch, "push", "origin", "feat/lease")
		val theirs = git(remote, "rev-parse", "refs/heads/feat/lease")
		git(dir, "commit", "--amend", "-m", "First, reworded")

		val ex = assertThrows<PushRejectedException> { service.push(project.id!!, id, force = true) }

		assertTrue(ex.lease)
		assertEquals(theirs, git(remote, "rev-parse", "refs/heads/feat/lease"))
	}

	@Test
	fun `a worktree whose folder is gone can't be pushed`() {
		val (id, dir) = addWorktree("feat/gone")
		FileSystemUtils.deleteRecursively(dir)

		assertThrows<WorktreeMissingException> { service.push(project.id!!, id, force = false) }
	}

	@Test
	fun `pushing with the remote deleted is a remote error`() {
		val (id, dir) = addWorktree("feat/orphan")
		commit(dir, "o.txt", "o\n", "Orphan")
		FileSystemUtils.deleteRecursively(remote)

		assertThrows<GitRemoteException> { service.push(project.id!!, id, force = false) }
	}

	private fun addWorktree(branch: String): Pair<Long, Path> {
		val view = service.add(project.id!!, branch, null)
		return view.worktree.id!! to Path.of(view.worktree.path)
	}
}
