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
import com.toptier.custom.github.exception.GitHubRefusedException
import com.toptier.custom.github.service.GitHubPull
import com.toptier.custom.github.service.GitHubPullWriteClient
import com.toptier.custom.github.service.GitHubRef
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.ticket.domain.Ticket
import com.toptier.custom.ticket.domain.TicketEvent
import com.toptier.custom.ticket.domain.TicketEventRepository
import com.toptier.custom.ticket.domain.TicketRepository
import com.toptier.custom.ticket.dto.CreateTicketRequest
import com.toptier.custom.ticket.exception.BranchNotPushedException
import com.toptier.custom.ticket.exception.InvalidTicketRequestException
import com.toptier.custom.ticket.exception.NothingToCompareException
import com.toptier.custom.ticket.exception.PullLinkSetException
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.TicketPullExistsException
import com.toptier.custom.ticket.exception.WorkspaceMissingException
import com.toptier.custom.ticket.service.TicketDetails
import com.toptier.custom.ticket.service.TicketGit
import com.toptier.custom.ticket.service.TicketHistory
import com.toptier.custom.ticket.service.TicketPulls
import com.toptier.custom.ticket.service.TicketService
import com.toptier.custom.ticket.service.TicketWorkspace
import com.toptier.custom.ticket.service.TicketWorktrees
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willAnswer
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.times
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opens ticket pull requests against `file://` bare remotes; GitHub is mocked. */
@DataJpaTest
@Import(
	TicketPulls::class, TicketService::class, TicketHistory::class, TicketWorkspace::class, TicketWorktrees::class,
	TicketDetails::class, TicketGit::class, WorktreeService::class, CheckoutService::class, WorktreeInspector::class,
	ProjectService::class, CheckoutLocks::class, GitRunner::class, CheckoutPaths::class, CheckoutTestConfig::class,
	FeatureTestConfig::class,
)
@EnableConfigurationProperties(FeatureProperties::class)
class TicketPullsTests {

	@Autowired
	lateinit var pullRequests: TicketPulls

	@Autowired
	lateinit var service: TicketService

	@Autowired
	lateinit var checkoutService: CheckoutService

	@Autowired
	lateinit var projectRepository: ProjectRepository

	@Autowired
	lateinit var checkouts: RepositoryCheckoutRepository

	@Autowired
	lateinit var tickets: TicketRepository

	@Autowired
	lateinit var events: TicketEventRepository

	@MockitoBean
	lateinit var github: GitHubPullWriteClient

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	@TempDir
	lateinit var tmp: Path

	private val created = mutableListOf<Created>()

	data class Created(val title: String, val head: String, val base: String, val body: String)

	@BeforeEach
	fun githubAnswers() {
		// Stubbed in the do-style so it can replace an earlier stub that throws
		willAnswer {
			created += Created(it.getArgument(2), it.getArgument(3), it.getArgument(4), it.getArgument(5))
			gitHubPull(7, it.getArgument(3), it.getArgument(4))
		}.given(github).create(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())
	}

	@AfterEach
	fun emptyRoot() {
		ROOT.listDirectoryEntries().forEach { FileSystemUtils.deleteRecursively(it) }
	}

	@Test
	fun `a pushed branch gets a pull request into the default branch, with its link saved and recorded`() {
		val (project, ticket) = ticketWithWorkspace(description = "Safari only")

		val opened = pullRequests.open(project.id!!, ticket.id!!, null, replaceLink = false)

		val pr = created.single()
		assertEquals("#1 Crash", pr.title)
		assertEquals(ticket.branch, pr.head)
		assertEquals("main", pr.base)
		assertTrue(pr.body.startsWith("Safari only\n"), pr.body)
		assertTrue("Ticket #1 (bug)" in pr.body, pr.body)
		assertTrue(
			"details.md: https://github.com/acme/factory/blob/bugs/1-crash/feature/bugs/1-crash/details.md" in pr.body,
			pr.body,
		)
		assertEquals(7, opened.pullNumber)
		assertEquals("https://github.com/acme/factory/pull/7", opened.pullRequestUrl)
		assertEquals("in_progress", opened.status.value)
		val entries = pullEvents(ticket)
		assertEquals(listOf("https://github.com/acme/factory/pull/7"), entries.map { it.newValue })
		assertNull(entries.single().oldValue)
		assertEquals("#1 Crash", entries.single().detail)
	}

	@Test
	fun `a title can be given`() {
		val (project, ticket) = ticketWithWorkspace()

		assertThrows<InvalidTicketRequestException> { pullRequests.open(project.id!!, ticket.id!!, "x".repeat(201), replaceLink = false) }
		assertTrue(created.isEmpty())

		pullRequests.open(project.id!!, ticket.id!!, "  Fix the crash  ", replaceLink = false)

		assertEquals("Fix the crash", created.single().title)
		assertTrue("No description." in created.single().body)
	}

	@Test
	fun `a second pull request is refused and GitHub is not asked again`() {
		val (project, ticket) = ticketWithWorkspace()
		pullRequests.open(project.id!!, ticket.id!!, null, replaceLink = false)

		assertThrows<TicketPullExistsException> { pullRequests.open(project.id!!, ticket.id!!, null, replaceLink = true) }

		then(github).should(times(1)).create(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())
		assertEquals(1, pullEvents(ticket).size)
	}

	@Test
	fun `a branch that isn't on GitHub or has nothing new is refused without calling GitHub`() {
		val (project, ticket) = ticketWithWorkspace()
		val clone = Path.of(checkouts.findByProjectId(project.id!!)!!.path)
		git(clone, "push", "origin", "refs/remotes/origin/main:refs/heads/${ticket.branch}", "--force")

		assertThrows<NothingToCompareException> { pullRequests.open(project.id!!, ticket.id!!, null, replaceLink = false) }

		git(clone, "push", "origin", "--delete", ticket.branch!!)

		assertThrows<BranchNotPushedException> { pullRequests.open(project.id!!, ticket.id!!, null, replaceLink = false) }

		then(github).should(never()).create(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())
		assertNull(tickets.findById(ticket.id!!).get().pullNumber)
		assertEquals(emptyList(), pullEvents(ticket))
	}

	@Test
	fun `a GitHub failure saves nothing and the pull request can be tried again`() {
		val (project, ticket) = ticketWithWorkspace()
		willThrow(GitHubRefusedException(403, "x"))
			.given(github).create(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())

		assertThrows<GitHubRefusedException> { pullRequests.open(project.id!!, ticket.id!!, null, replaceLink = false) }

		assertNull(tickets.findById(ticket.id!!).get().pullNumber)
		assertNull(tickets.findById(ticket.id!!).get().pullRequestUrl)
		assertEquals(emptyList(), pullEvents(ticket))

		githubAnswers()
		assertEquals(7, pullRequests.open(project.id!!, ticket.id!!, null, replaceLink = false).pullNumber)
	}

	@Test
	fun `a link typed by hand is replaced only when confirmed, and the old one is kept in the entry`() {
		val (project, ticket) = ticketWithWorkspace()
		service.edit(project.id!!, ticket.id!!, mapOf("pullRequestUrl" to "https://example.com/pr/1"))

		assertThrows<PullLinkSetException> { pullRequests.open(project.id!!, ticket.id!!, null, replaceLink = false) }
		assertTrue(created.isEmpty())

		val opened = pullRequests.open(project.id!!, ticket.id!!, null, replaceLink = true)

		assertEquals("https://github.com/acme/factory/pull/7", opened.pullRequestUrl)
		assertEquals("https://example.com/pr/1", pullEvents(ticket).single().oldValue)
	}

	@Test
	fun `no workspace and an archived project are refused`() {
		val (project, ticket) = ticketWithWorkspace()
		val never = service.create(project.id!!, CreateTicketRequest(type = "bug", title = "Untouched"))

		assertThrows<WorkspaceMissingException> { pullRequests.open(project.id!!, never.id!!, null, replaceLink = false) }

		project.archived = true
		projectRepository.saveAndFlush(project)
		assertThrows<TicketProjectArchivedException> { pullRequests.open(project.id!!, ticket.id!!, null, replaceLink = false) }
		then(github).should(never()).create(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())
	}

	private fun pullEvents(ticket: Ticket): List<TicketEvent> =
		events.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.id!!).filter { it.kind == TicketEvent.KIND_PULL }

	/** A project (GitHub URL acme/factory) with a ready checkout and a bug "Crash" whose workspace is built and pushed. */
	private fun ticketWithWorkspace(description: String? = null): Pair<Project, Ticket> {
		val remote = GitFixtures.bareRemote(Files.createTempDirectory(tmp, "remote"))
		val project = projectRepository.saveAndFlush(Project().apply {
			name = "Project ${remote.hashCode()}"
			nameKey = name.lowercase()
			repositoryUrl = fileUrl(remote)
			createdAt = START
			updatedAt = START
		})
		checkoutService.start(project.id!!)
		// The clone keeps its file:// origin; pull request calls need a GitHub URL to parse
		project.repositoryUrl = "https://github.com/acme/factory"
		projectRepository.saveAndFlush(project)
		val created = service.create(project.id!!, CreateTicketRequest(type = "bug", title = "Crash", description = description))
		service.changeStatus(project.id!!, created.id!!, "open")
		val ticket = service.changeStatus(project.id!!, created.id!!, "in_progress")
		assertEquals("ready", ticket.workspaceState)
		return project to ticket
	}

	private fun gitHubPull(number: Int, head: String, base: String) = GitHubPull(
		number = number,
		title = "PR $number",
		state = "open",
		user = null,
		head = GitHubRef(head, "acme:$head", null, "sha$number"),
		base = GitHubRef(base, "acme:$base", null),
		body = null,
		createdAt = START,
		updatedAt = START,
		mergedAt = null,
		closedAt = null,
		htmlUrl = "https://github.com/acme/factory/pull/$number",
	)
}
