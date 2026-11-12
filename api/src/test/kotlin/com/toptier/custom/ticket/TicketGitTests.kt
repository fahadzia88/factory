package com.toptier.custom.ticket

import com.toptier.custom.checkout.CheckoutTestConfig
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.ROOT
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.GitFixtures
import com.toptier.custom.checkout.GitFixtures.fileUrl
import com.toptier.custom.checkout.GitFixtures.git
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
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
import com.toptier.custom.ticket.dto.CreateTicketRequest
import com.toptier.custom.ticket.exception.InvalidTicketRequestException
import com.toptier.custom.ticket.exception.NothingCommittedException
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.WorkspaceMissingException
import com.toptier.custom.ticket.service.TicketDetails
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Commits and pushes ticket branches against `file://` bare remotes. */
@DataJpaTest
@Import(
	TicketService::class, TicketHistory::class, TicketWorkspace::class, TicketWorktrees::class, TicketDetails::class,
	TicketGit::class, WorktreeService::class, CheckoutService::class, WorktreeInspector::class, ProjectService::class,
	CheckoutLocks::class, GitRunner::class, CheckoutPaths::class, CheckoutTestConfig::class, FeatureTestConfig::class,
)
@EnableConfigurationProperties(FeatureProperties::class)
class TicketGitTests {

	@Autowired
	lateinit var service: TicketService

	@Autowired
	lateinit var ticketGit: TicketGit

	@Autowired
	lateinit var checkoutService: CheckoutService

	@Autowired
	lateinit var projectRepository: ProjectRepository

	@Autowired
	lateinit var checkouts: RepositoryCheckoutRepository

	@Autowired
	lateinit var worktrees: TicketWorktrees

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
	fun `a clean worktree commits nothing and writes no entry`() {
		val (project, ticket) = ticketWithWorkspace()

		val result = ticketGit.commit(project.id!!, ticket.id!!, "Nothing")

		assertFalse(result.committed)
		assertNull(result.sha)
		assertEquals(emptyList(), eventsOf(ticket, TicketEvent.KIND_COMMIT))
	}

	@Test
	fun `a dirty worktree is committed whole with the given message, or a default one`() {
		val (project, ticket) = ticketWithWorkspace()
		val dir = dir(project, ticket)
		Files.writeString(dir.resolve("src.txt"), "fix\n")
		Files.writeString(dir.resolve("${ticket.folder}/details.md"), "# changed\n")

		val first = ticketGit.commit(project.id!!, ticket.id!!, "  Fix the cookie  ")

		assertTrue(first.committed)
		assertEquals(git(dir, "rev-parse", "--short", "HEAD"), first.sha)
		assertEquals("Fix the cookie", git(dir, "log", "-1", "--format=%s"))
		assertEquals("", git(dir, "status", "--porcelain"))
		assertEquals(listOf("src.txt", "${ticket.folder}/details.md").sorted(), git(dir, "show", "--name-only", "--format=", "HEAD").lines().sorted())
		Files.writeString(dir.resolve("more.txt"), "more\n")

		val second = ticketGit.commit(project.id!!, ticket.id!!, null)

		assertTrue(second.committed)
		assertEquals("#1 Crash", git(dir, "log", "-1", "--format=%s"))
		val entries = eventsOf(ticket, TicketEvent.KIND_COMMIT)
		assertEquals(listOf(first.sha, second.sha), entries.map { it.newValue })
		assertEquals("Fix the cookie", entries[0].detail)
	}

	@Test
	fun `a message over 200 characters is refused`() {
		val (project, ticket) = ticketWithWorkspace()
		Files.writeString(dir(project, ticket).resolve("src.txt"), "fix\n")

		assertThrows<InvalidTicketRequestException> { ticketGit.commit(project.id!!, ticket.id!!, "x".repeat(201)) }

		assertEquals(emptyList(), eventsOf(ticket, TicketEvent.KIND_COMMIT))
		assertTrue(git(dir(project, ticket), "status", "--porcelain").isNotBlank())
	}

	@Test
	fun `a push sends the branch and writes an entry, and a later commit can be pushed too`() {
		val (project, ticket) = ticketWithWorkspace()
		val dir = dir(project, ticket)
		val clone = Path.of(checkouts.findByProjectId(project.id!!)!!.path)
		Files.writeString(dir.resolve("src.txt"), "fix\n")
		ticketGit.commit(project.id!!, ticket.id!!, "Fix")

		val pushed = ticketGit.push(project.id!!, ticket.id!!)

		assertEquals(ticket.branch, pushed.branch)
		assertEquals(git(dir, "rev-parse", "--short", "HEAD"), pushed.head)
		assertEquals(git(dir, "rev-parse", "HEAD"), git(clone, "rev-parse", "refs/remotes/origin/${ticket.branch}"))
		val entries = eventsOf(ticket, TicketEvent.KIND_PUSH)
		assertEquals(listOf(pushed.head), entries.map { it.newValue })
		assertEquals(ticket.branch, entries.single().detail)
	}

	@Test
	fun `a branch with no commit beyond the default branch can't be pushed`() {
		val (project, ticket) = ticketWithWorkspace()
		val dir = dir(project, ticket)
		git(dir, "reset", "--hard", "refs/remotes/origin/main")

		assertThrows<NothingCommittedException> { ticketGit.push(project.id!!, ticket.id!!) }

		assertEquals(emptyList(), eventsOf(ticket, TicketEvent.KIND_PUSH))
	}

	@Test
	fun `a refused push writes no entry`() {
		val (project, ticket, remote) = ticketWithWorkspace()
		val dir = dir(project, ticket)
		Files.writeString(dir.resolve("src.txt"), "fix\n")
		ticketGit.commit(project.id!!, ticket.id!!, "Fix")
		val hook = remote.resolve("hooks/pre-receive")
		Files.createDirectories(hook.parent)
		Files.writeString(hook, "#!/bin/sh\necho 'no pushes today' >&2\nexit 1\n")
		hook.toFile().setExecutable(true)

		assertThrows<Exception> { ticketGit.push(project.id!!, ticket.id!!) }

		assertEquals(emptyList(), eventsOf(ticket, TicketEvent.KIND_PUSH))
	}

	@Test
	fun `another branch checked out in the worktree is left alone`() {
		val (project, ticket) = ticketWithWorkspace()
		val dir = dir(project, ticket)
		git(dir, "checkout", "-b", "other")
		Files.writeString(dir.resolve("src.txt"), "fix\n")

		assertThrows<WorkspaceMissingException> { ticketGit.commit(project.id!!, ticket.id!!, "Fix") }
		assertThrows<WorkspaceMissingException> { ticketGit.push(project.id!!, ticket.id!!) }

		assertTrue(git(dir, "status", "--porcelain").isNotBlank())
		assertEquals(emptyList(), eventsOf(ticket, TicketEvent.KIND_COMMIT))
	}

	@Test
	fun `without a workspace there is nothing to commit or push`() {
		val (project, _, _) = ticketWithWorkspace()
		val never = service.create(project.id!!, CreateTicketRequest(type = "bug", title = "Untouched"))

		assertThrows<WorkspaceMissingException> { ticketGit.commit(project.id!!, never.id!!, "x") }
		assertThrows<WorkspaceMissingException> { ticketGit.push(project.id!!, never.id!!) }
	}

	@Test
	fun `an archived project refuses both`() {
		val (project, ticket) = ticketWithWorkspace()
		Files.writeString(dir(project, ticket).resolve("src.txt"), "fix\n")
		project.archived = true
		projectRepository.saveAndFlush(project)

		assertThrows<TicketProjectArchivedException> { ticketGit.commit(project.id!!, ticket.id!!, "Fix") }
		assertThrows<TicketProjectArchivedException> { ticketGit.push(project.id!!, ticket.id!!) }

		assertTrue(git(dir(project, ticket), "status", "--porcelain").isNotBlank())
	}

	private fun eventsOf(ticket: Ticket, kind: String): List<TicketEvent> =
		events.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.id!!).filter { it.kind == kind }

	private fun dir(project: Project, ticket: Ticket): Path = Path.of(worktrees.find(project.id!!, ticket.branch!!)!!.path)

	private data class Setup(val project: Project, val ticket: Ticket, val remote: Path)

	/** A project with a ready checkout and a bug "Crash" whose workspace is built (branch pushed, details committed). */
	private fun ticketWithWorkspace(): Setup {
		val remote = GitFixtures.bareRemote(Files.createTempDirectory(tmp, "remote"))
		val project = projectRepository.saveAndFlush(Project().apply {
			name = "Project ${remote.hashCode()}"
			nameKey = name.lowercase()
			repositoryUrl = fileUrl(remote)
			createdAt = START
			updatedAt = START
		})
		checkoutService.start(project.id!!)
		val created = service.create(project.id!!, CreateTicketRequest(type = "bug", title = "Crash"))
		service.changeStatus(project.id!!, created.id!!, "open")
		val ticket = service.changeStatus(project.id!!, created.id!!, "in_progress")
		assertEquals("ready", ticket.workspaceState)
		return Setup(project, ticket, remote)
	}
}
