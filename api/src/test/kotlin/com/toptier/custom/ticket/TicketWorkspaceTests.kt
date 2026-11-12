package com.toptier.custom.ticket

import com.toptier.custom.checkout.CheckoutTestConfig
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.ROOT
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.GitFixtures
import com.toptier.custom.checkout.GitFixtures.fileUrl
import com.toptier.custom.checkout.GitFixtures.git
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.CheckoutPaths
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeInspector
import com.toptier.custom.checkout.service.WorktreeService
import com.toptier.custom.feature.FeatureTestConfig
import com.toptier.custom.feature.config.FeatureProperties
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketEvent
import com.toptier.custom.ticket.domain.TicketEventRepository
import com.toptier.custom.ticket.domain.TicketRepository
import com.toptier.custom.ticket.dto.CreateTicketRequest
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.WorkspaceNotNeededException
import com.toptier.custom.ticket.service.TicketDetails
import com.toptier.custom.ticket.service.TicketDetailsTemplate
import com.toptier.custom.ticket.service.TicketGit
import com.toptier.custom.ticket.service.TicketHistory
import com.toptier.custom.ticket.service.TicketService
import com.toptier.custom.ticket.service.TicketWorkspace
import com.toptier.custom.ticket.service.TicketWorktrees
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.util.FileSystemUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds ticket workspaces against `file://` bare remotes. */
@DataJpaTest
@Import(
	TicketService::class, TicketHistory::class, TicketWorkspace::class, TicketWorktrees::class, TicketDetails::class, TicketGit::class,
	WorktreeService::class, CheckoutService::class, WorktreeInspector::class, ProjectService::class,
	CheckoutLocks::class, GitRunner::class, CheckoutPaths::class, CheckoutTestConfig::class, FeatureTestConfig::class,
)
@EnableConfigurationProperties(FeatureProperties::class)
class TicketWorkspaceTests {

	@Autowired
	lateinit var service: TicketService

	@Autowired
	lateinit var checkoutService: CheckoutService

	@Autowired
	lateinit var projectRepository: ProjectRepository

	@Autowired
	lateinit var checkouts: RepositoryCheckoutRepository

	@Autowired
	lateinit var worktreeRows: RepositoryWorktreeRepository

	@Autowired
	lateinit var worktrees: TicketWorktrees

	@Autowired
	lateinit var tickets: TicketRepository

	@Autowired
	lateinit var events: TicketEventRepository

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	@TempDir
	lateinit var tmp: Path

	@AfterEach
	fun emptyRoot() {
		ROOT.listDirectoryEntries().forEach { FileSystemUtils.deleteRecursively(it) }
	}

	@Test
	fun `the first in progress of a bug builds the worktree, details file and pushed branch`() {
		val (project, _) = projectWithRemote()
		val ticket = ticketInProgress(project, "bug", "Login fails on Safari!", "Safari only")

		assertEquals("in_progress", ticket.status.value)
		assertEquals("ready", ticket.workspaceState)
		assertNull(ticket.workspaceError)
		assertEquals("login-fails-on-safari", ticket.slug)
		assertEquals("bugs/1-login-fails-on-safari", ticket.branch)
		assertEquals("feature/bugs/1-login-fails-on-safari", ticket.folder)
		val worktree = worktrees.find(project.id!!, ticket.branch!!)!!
		assertTrue(!worktree.missing)
		val dir = Path.of(worktree.path)
		val content = Files.readString(dir.resolve("${ticket.folder}/details.md"))
		assertContains(content, "# #1 Login fails on Safari!")
		assertContains(content, "Safari only")
		assertEquals(TicketDetailsTemplate.sha256(content), ticket.detailsBaseline)
		val clone = Path.of(checkouts.findByProjectId(project.id!!)!!.path)
		assertEquals(git(dir, "rev-parse", "HEAD"), git(clone, "rev-parse", "refs/remotes/origin/${ticket.branch}"))
		assertEquals(git(clone, "rev-parse", "refs/remotes/origin/main"), git(dir, "rev-parse", "HEAD~1"))
		assertEquals("#1 Login fails on Safari!: details", git(dir, "log", "-1", "--format=%s"))
		assertEquals("", git(dir, "status", "--porcelain"))
		assertEquals(
			listOf("worktree", "details", "push"),
			workspaceEvents(ticket).map { it.field },
		)
	}

	@Test
	fun `an improvement gets an updates branch and folder`() {
		val (project, _) = projectWithRemote()
		val ticket = ticketInProgress(project, "improvement", "Dark mode")

		assertEquals("ready", ticket.workspaceState)
		assertEquals("updates/1-dark-mode", ticket.branch)
		assertEquals("feature/updates/1-dark-mode", ticket.folder)
		val worktree = worktrees.find(project.id!!, ticket.branch!!)!!
		assertTrue(Files.exists(Path.of(worktree.path).resolve("feature/updates/1-dark-mode/details.md")))
		assertEquals(3, workspaceEvents(ticket).size)
	}

	@Test
	fun `a later in progress reuses the workspace and writes nothing new`() {
		val (project, _) = projectWithRemote()
		val ticket = ticketInProgress(project, "bug", "Crash")
		val rows = worktreeRows.count()
		for (status in listOf("in_review", "merged", "deployed", "reopened", "in_progress")) {
			service.changeStatus(project.id!!, ticket.id!!, status)
		}

		val again = service.get(project.id!!, ticket.id!!)
		assertEquals("in_progress", again.status.value)
		assertEquals("ready", again.workspaceState)
		assertEquals(rows, worktreeRows.count())
		assertEquals(3, workspaceEvents(ticket).size)
		assertEquals("bugs/1-crash", again.branch)
	}

	@Test
	fun `a failed push keeps the status, records the failure and a retry finishes without duplicates`() {
		val (project, remote) = projectWithRemote()
		val hook = remote.resolve("hooks/pre-receive")
		Files.createDirectories(hook.parent)
		Files.writeString(hook, "#!/bin/sh\necho 'no pushes today' >&2\nexit 1\n")
		hook.toFile().setExecutable(true)

		val failed = ticketInProgress(project, "bug", "Broken push")

		assertEquals("in_progress", failed.status.value)
		assertEquals("failed", failed.workspaceState)
		assertNotNull(failed.workspaceError)
		assertEquals(listOf("worktree", "details", "push"), workspaceEvents(failed).map { it.field })
		assertTrue(workspaceEvents(failed).last().detail!!.startsWith("Failed:"))
		val rows = worktreeRows.count()
		Files.delete(hook)

		val retried = service.retryWorkspace(project.id!!, failed.id!!)

		assertEquals("ready", retried.workspaceState)
		assertNull(retried.workspaceError)
		assertEquals(rows, worktreeRows.count())
		val dir = Path.of(worktrees.find(project.id!!, retried.branch!!)!!.path)
		assertEquals(1, git(dir, "log", "--oneline", "refs/remotes/origin/main..HEAD").lines().size)
		val clone = Path.of(checkouts.findByProjectId(project.id!!)!!.path)
		assertEquals(git(dir, "rev-parse", "HEAD"), git(clone, "rev-parse", "refs/remotes/origin/${retried.branch}"))
		assertEquals(listOf("worktree", "details", "push", "push"), workspaceEvents(retried).map { it.field })
	}

	@Test
	fun `a retry is refused when nothing failed`() {
		val (project, _) = projectWithRemote()
		val never = service.create(project.id!!, CreateTicketRequest(type = "bug", title = "Untouched"))
		val ready = ticketInProgress(project, "bug", "Done")

		assertThrows<WorkspaceNotNeededException> { service.retryWorkspace(project.id!!, never.id!!) }
		assertThrows<WorkspaceNotNeededException> { service.retryWorkspace(project.id!!, ready.id!!) }
		assertEquals("none", service.get(project.id!!, never.id!!).workspaceState)
	}

	@Test
	fun `an archived project builds no workspace and refuses a retry`() {
		val (project, _) = projectWithRemote()
		val ticket = service.create(project.id!!, CreateTicketRequest(type = "bug", title = "Old"))
		service.changeStatus(project.id!!, ticket.id!!, "open")
		project.archived = true
		projectRepository.saveAndFlush(project)

		assertThrows<TicketProjectArchivedException> { service.changeStatus(project.id!!, ticket.id!!, "in_progress") }
		assertThrows<TicketProjectArchivedException> { service.retryWorkspace(project.id!!, ticket.id!!) }

		val stored = service.get(project.id!!, ticket.id!!)
		assertEquals("open", stored.status.value)
		assertEquals("none", stored.workspaceState)
		assertNull(stored.branch)
	}

	private fun workspaceEvents(ticket: Ticket): List<TicketEvent> =
		events.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.id!!).filter { it.kind == TicketEvent.KIND_WORKSPACE }

	/** Files a ticket and moves it filed → open → in progress; returns it as stored afterwards. */
	private fun ticketInProgress(project: Project, type: String, title: String, description: String? = null): Ticket {
		val created = service.create(project.id!!, CreateTicketRequest(type = type, title = title, description = description))
		service.changeStatus(project.id!!, created.id!!, "open")
		return service.changeStatus(project.id!!, created.id!!, "in_progress")
	}

	private fun projectWithRemote(): Pair<Project, Path> {
		val remote = GitFixtures.bareRemote(Files.createTempDirectory(tmp, "remote"))
		val project = projectRepository.saveAndFlush(Project().apply {
			name = "Project ${remote.hashCode()}"
			nameKey = name.lowercase()
			repositoryUrl = fileUrl(remote)
			createdAt = START
			updatedAt = START
		})
		checkoutService.start(project.id!!)
		return project to remote
	}
}
