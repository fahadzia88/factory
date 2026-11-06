package com.toptier.custom.checkout

import com.toptier.custom.checkout.CheckoutTestConfig.Companion.ROOT
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.GitFixtures.bareRemote
import com.toptier.custom.checkout.GitFixtures.fileUrl
import com.toptier.custom.checkout.GitFixtures.git
import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckout
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.checkout.exception.CheckoutExistsException
import com.toptier.custom.checkout.exception.CheckoutNotFoundException
import com.toptier.custom.checkout.exception.GitCommandException
import com.toptier.custom.checkout.exception.GitTimeoutException
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.CheckoutPaths
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeInspector
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
import org.springframework.util.FileSystemUtils
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@Import(
	CheckoutService::class, ProjectService::class, CheckoutLocks::class, GitRunner::class, CheckoutPaths::class, WorktreeInspector::class,
	CheckoutTestConfig::class,
)
class CheckoutServiceTests {

	@Autowired
	lateinit var service: CheckoutService

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

	@BeforeEach
	fun resetClock() {
		clock.now = START
	}

	@AfterEach
	fun emptyRoot() {
		ROOT.listDirectoryEntries().forEach { FileSystemUtils.deleteRecursively(it) }
	}

	@Test
	fun `start clones the default branch and records the main worktree`() {
		val remote = bareRemote(tmp)
		val project = project(fileUrl(remote))

		val view = service.start(project.id!!)

		val checkout = checkouts.findById(view.checkout.id!!).get()
		assertEquals(CheckoutState.READY, checkout.state)
		assertEquals("main", checkout.defaultBranch)
		assertEquals(START, checkout.lastFetchedAt)
		assertNull(checkout.error)
		val dir = ROOT.resolve(project.id.toString())
		assertEquals(dir.toString(), checkout.path)
		val rows = worktrees.findByCheckoutIdOrderByMainDescCreatedAtAscIdAsc(checkout.id!!)
		assertEquals(1, rows.size)
		rows[0].let {
			assertEquals("main", it.branch)
			assertEquals(dir.toString(), it.path)
			assertTrue(it.main)
			assertNull(it.baseBranch)
		}
		assertEquals(git(remote, "rev-parse", "main"), git(dir, "rev-parse", "HEAD"))
		assertEquals(listOf("main"), service.get(project.id!!).worktrees.map { it.worktree.branch })
	}

	@Test
	fun `the default branch comes from the remote, and origin has no token`() {
		val remote = bareRemote(tmp, branch = "trunk")
		val url = fileUrl(remote)
		val project = project(url)

		service.start(project.id!!)

		val checkout = checkouts.findByProjectId(project.id!!)!!
		assertEquals("trunk", checkout.defaultBranch)
		val dir = ROOT.resolve(project.id.toString())
		assertEquals(url, git(dir, "config", "--get", "remote.origin.url"))
		assertFalse(dir.resolve(".git/config").readText().contains("test-token"))
	}

	@Test
	fun `start refuses a checkout that is ready and changes nothing`() {
		val project = project(fileUrl(bareRemote(tmp)))
		service.start(project.id!!)
		clock.advance(Duration.ofMinutes(5))

		assertThrows<CheckoutExistsException> { service.start(project.id!!) }

		val checkout = checkouts.findByProjectId(project.id!!)!!
		assertEquals(CheckoutState.READY, checkout.state)
		assertEquals(START, checkout.createdAt)
		assertEquals(1, worktrees.findByCheckoutIdOrderByMainDescCreatedAtAscIdAsc(checkout.id!!).size)
	}

	@Test
	fun `a failed clone leaves no folder, and a retry reuses the row`() {
		val project = project(fileUrl(tmp.resolve("missing.git")))

		val failed = service.start(project.id!!)

		val id = failed.checkout.id!!
		checkouts.findById(id).get().let {
			assertEquals(CheckoutState.FAILED, it.state)
			assertEquals("GitHub couldn't find the repository, or the git token can't see it.", it.error)
		}
		assertFalse(Files.exists(ROOT.resolve(project.id.toString())))
		assertTrue(service.get(project.id!!).worktrees.isEmpty())

		project.repositoryUrl = fileUrl(bareRemote(tmp))
		projectRepository.saveAndFlush(project)
		clock.advance(Duration.ofMinutes(1))
		val retried = service.start(project.id!!)

		assertEquals(id, retried.checkout.id)
		checkouts.findById(id).get().let {
			assertEquals(CheckoutState.READY, it.state)
			assertNull(it.error)
			assertEquals(START.plus(Duration.ofMinutes(1)), it.createdAt)
		}
		assertEquals(1, checkouts.count())
	}

	@Test
	fun `cloneFailure explains timeouts, rejected tokens and unreachable hosts`() {
		assertEquals(
			"The clone took longer than 10 minutes.",
			service.cloneFailure(GitTimeoutException(Duration.ofMinutes(10)), Duration.ofMinutes(10)),
		)
		assertEquals(
			"GitHub rejected the git token. Check that API_GIT_TOKEN has Contents: read and write on this repository.",
			service.cloneFailure(GitCommandException("clone", 128, "fatal: Authentication failed for 'https://github.com/a/b/'"), Duration.ofMinutes(10)),
		)
		assertEquals(
			"GitHub couldn't be reached.",
			service.cloneFailure(GitCommandException("clone", 128, "fatal: unable to access 'https://github.com/a/b/': Could not resolve host: github.com"), Duration.ofMinutes(10)),
		)
		assertEquals(
			"The clone failed (git exit 2).",
			service.cloneFailure(GitCommandException("clone", 2, "fatal: something else"), Duration.ofMinutes(10)),
		)
		assertEquals("The clone failed unexpectedly.", service.cloneFailure(IllegalStateException("boom"), Duration.ofMinutes(10)))
	}

	@Test
	fun `failInterruptedClones fails cloning rows and leaves ready ones`() {
		val cloning = checkouts.saveAndFlush(checkout(1, CheckoutState.CLONING)).id!!
		val ready = checkouts.saveAndFlush(checkout(2, CheckoutState.READY)).id!!

		service.failInterruptedClones()

		checkouts.findById(cloning).get().let {
			assertEquals(CheckoutState.FAILED, it.state)
			assertEquals("The clone was interrupted by a restart.", it.error)
		}
		checkouts.findById(ready).get().let {
			assertEquals(CheckoutState.READY, it.state)
			assertNull(it.error)
		}
	}

	@Test
	fun `unknown projects and missing checkouts are reported`() {
		assertThrows<ProjectNotFoundException> { service.get(999) }
		assertThrows<ProjectNotFoundException> { service.start(999) }

		val project = project(fileUrl(bareRemote(tmp)))
		assertThrows<CheckoutNotFoundException> { service.get(project.id!!) }
	}

	private fun project(url: String) = projectRepository.saveAndFlush(Project().apply {
		name = "Project ${url.hashCode()}"
		nameKey = name.lowercase()
		repositoryUrl = url
		createdAt = START
		updatedAt = START
	})

	private fun checkout(projectId: Long, state: CheckoutState) = RepositoryCheckout().apply {
		this.projectId = projectId
		path = ROOT.resolve(projectId.toString()).toString()
		this.state = state
		createdAt = Instant.EPOCH
	}
}
