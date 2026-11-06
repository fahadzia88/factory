package com.toptier.custom.checkout

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.ROOT
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.GitFixtures.bareRemote
import com.toptier.custom.checkout.GitFixtures.commit
import com.toptier.custom.checkout.GitFixtures.fileUrl
import com.toptier.custom.checkout.GitFixtures.git
import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckout
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.exception.CheckoutNotFoundException
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.checkout.exception.GitFailure
import com.toptier.custom.checkout.exception.GitRemoteException
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.CheckoutPaths
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.GitRunner
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
import org.slf4j.LoggerFactory
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

@DataJpaTest
@Import(
	CheckoutService::class, ProjectService::class, CheckoutLocks::class, GitRunner::class, CheckoutPaths::class,
	CheckoutTestConfig::class,
)
class CheckoutFetchTests {

	@Autowired
	lateinit var service: CheckoutService

	@Autowired
	lateinit var projectRepository: ProjectRepository

	@Autowired
	lateinit var checkouts: RepositoryCheckoutRepository

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
	fun `fetch fast-forwards a clean main worktree and records the time`() {
		val remote = bareRemote(tmp)
		val (project, dir) = ready(remote)
		val head = push(remote, "main", "new.txt")
		clock.advance(Duration.ofMinutes(5))

		val view = service.fetch(project.id!!)

		assertEquals(head, git(dir, "rev-parse", "HEAD"))
		assertEquals(START.plus(Duration.ofMinutes(5)), view.checkout.lastFetchedAt)
		assertEquals(START.plus(Duration.ofMinutes(5)), checkouts.findByProjectId(project.id!!)!!.lastFetchedAt)
		assertEquals(listOf("main"), view.worktrees.map { it.worktree.branch })
	}

	@Test
	fun `fetch leaves a dirty main worktree where it was`() {
		val remote = bareRemote(tmp)
		val (project, dir) = ready(remote)
		val before = git(dir, "rev-parse", "HEAD")
		Files.writeString(dir.resolve("untracked.txt"), "work in progress\n")
		val head = push(remote, "main", "new.txt")
		clock.advance(Duration.ofMinutes(5))

		service.fetch(project.id!!)

		assertEquals(before, git(dir, "rev-parse", "HEAD"))
		assertEquals(head, git(dir, "rev-parse", "origin/main"))
		assertEquals(START.plus(Duration.ofMinutes(5)), checkouts.findByProjectId(project.id!!)!!.lastFetchedAt)
	}

	@Test
	fun `fetch never moves other worktrees`() {
		val remote = bareRemote(tmp)
		val (project, dir) = ready(remote)
		val tree = ROOT.resolve("${project.id}.worktrees").resolve("feat__x")
		git(dir, "worktree", "add", "-b", "feat/x", tree.toString(), "origin/main")
		val before = git(tree, "rev-parse", "HEAD")
		val pushed = push(remote, "feat/x", "feature.txt")

		service.fetch(project.id!!)

		assertEquals(before, git(tree, "rev-parse", "HEAD"))
		assertEquals(pushed, git(dir, "rev-parse", "origin/feat/x"))
	}

	@Test
	fun `fetch needs a ready checkout`() {
		val project = project(fileUrl(tmp.resolve("none.git")))
		assertThrows<CheckoutNotFoundException> { service.fetch(project.id!!) }

		checkouts.saveAndFlush(RepositoryCheckout().apply {
			projectId = project.id!!
			path = ROOT.resolve(project.id.toString()).toString()
			state = CheckoutState.FAILED
			createdAt = START
		})
		assertThrows<CheckoutNotReadyException> { service.fetch(project.id!!) }
	}

	@Test
	fun `fetch from a deleted remote is a remote error`() {
		val remote = bareRemote(tmp)
		val (project, _) = ready(remote)
		FileSystemUtils.deleteRecursively(remote)

		val ex = assertThrows<GitRemoteException> { service.fetch(project.id!!) }

		assertEquals(GitFailure.NOT_FOUND, ex.failure)
		assertEquals(START, checkouts.findByProjectId(project.id!!)!!.lastFetchedAt)
	}

	@Test
	fun `fetchAll fetches every ready checkout and logs a failure without the token`() {
		val good = bareRemote(tmp)
		val bad = bareRemote(tmp.resolve("test-token"))
		val (goodProject, goodDir) = ready(good)
		val (badProject, _) = ready(bad)
		FileSystemUtils.deleteRecursively(bad)
		val head = push(good, "main", "new.txt")
		clock.advance(Duration.ofMinutes(15))
		val logger = LoggerFactory.getLogger(CheckoutService::class.java) as Logger
		val appender = ListAppender<ILoggingEvent>().apply { start() }
		logger.addAppender(appender)

		try {
			service.fetchAll()
		} finally {
			logger.detachAppender(appender)
		}

		assertEquals(head, git(goodDir, "rev-parse", "HEAD"))
		assertEquals(START.plus(Duration.ofMinutes(15)), checkouts.findByProjectId(goodProject.id!!)!!.lastFetchedAt)
		val badRow = checkouts.findByProjectId(badProject.id!!)!!
		assertEquals(CheckoutState.READY, badRow.state)
		assertEquals(START, badRow.lastFetchedAt)
		assertEquals(1, appender.list.count { it.level == Level.WARN })
		appender.list.forEach { assertFalse(it.formattedMessage.contains("test-token"), it.formattedMessage) }
	}

	/** A project on [remote] with a `READY` checkout; returns it and the checkout's folder. */
	private fun ready(remote: Path): Pair<Project, Path> {
		val project = project(fileUrl(remote))
		val view = service.start(project.id!!)
		assertEquals(CheckoutState.READY, checkouts.findById(view.checkout.id!!).get().state)
		return project to Path.of(view.checkout.path)
	}

	/** Pushes a new commit on [branch] to [remote] from a scratch clone; returns its full SHA. */
	private fun push(remote: Path, branch: String, file: String): String {
		val scratch = Files.createTempDirectory(tmp, "scratch")
		git(scratch, "clone", fileUrl(remote), ".")
		if (git(scratch, "ls-remote", "--heads", "origin", branch).isBlank()) {
			git(scratch, "checkout", "-b", branch)
		} else {
			git(scratch, "checkout", branch)
		}
		commit(scratch, file, "$file\n", "Add $file")
		git(scratch, "push", "origin", branch)
		return git(scratch, "rev-parse", "HEAD")
	}

	private fun project(url: String) = projectRepository.saveAndFlush(Project().apply {
		name = "Project ${url.hashCode()}"
		nameKey = name.lowercase()
		repositoryUrl = url
		createdAt = START
		updatedAt = START
	})
}
