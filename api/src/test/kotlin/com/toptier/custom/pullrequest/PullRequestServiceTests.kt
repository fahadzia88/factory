package com.toptier.custom.pullrequest

import com.toptier.custom.github.exception.GitHubNotFoundException
import com.toptier.custom.github.service.GitHubFile
import com.toptier.custom.github.service.GitHubPage
import com.toptier.custom.github.service.GitHubPull
import com.toptier.custom.github.service.GitHubPullRequestClient
import com.toptier.custom.github.service.GitHubRef
import com.toptier.custom.github.service.GitHubRefRepo
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.github.service.GitHubUser
import com.toptier.custom.github.service.RepositoryCheck
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectStatus
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.pullrequest.exception.InvalidPullRequestParameterException
import com.toptier.custom.pullrequest.exception.PullRequestNotFoundException
import com.toptier.custom.pullrequest.exception.PullRequestRepositoryNotFoundException
import com.toptier.custom.pullrequest.service.PullRequestService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mockito.mock
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PullRequestServiceTests {

	private val projects: ProjectService = mock(ProjectService::class.java)
	private val client: GitHubPullRequestClient = mock(GitHubPullRequestClient::class.java)
	private val checker: GitHubRepositoryChecker = mock(GitHubRepositoryChecker::class.java)
	private val service = PullRequestService(projects, client, checker)

	@Test
	fun `lists a page of open pull requests for the project's repository`() {
		given(projects.get(7)).willReturn(project())
		given(client.listOpen("acme", "factory", 2)).willReturn(GitHubPage(listOf(pull(), forkPull()), hasNext = true))

		val page = service.list(7, 2)

		assertEquals(2, page.page)
		assertEquals(20, page.size)
		assertTrue(page.hasNext)
		val (own, fork) = page.items
		assertEquals("feat/list", own.headRef)
		assertEquals("main", own.baseRef)
		assertEquals("octocat", own.author?.login)
		assertEquals("open", own.state)
		assertEquals("someone:fix", fork.headRef)
		assertNull(fork.author)
		assertTrue(fork.draft)
	}

	@Test
	fun `defaults to the first page`() {
		given(projects.get(7)).willReturn(project())
		given(client.listOpen("acme", "factory", 1)).willReturn(GitHubPage(emptyList(), hasNext = false))

		val page = service.list(7, null)

		assertEquals(1, page.page)
		assertTrue(page.items.isEmpty())
		assertFalse(page.hasNext)
	}

	@Test
	fun `lists for archived and unverified projects`() {
		given(projects.get(7)).willReturn(project(archived = true, verified = false))
		given(client.listOpen("acme", "factory", 1)).willReturn(GitHubPage(listOf(pull()), hasNext = false))

		assertEquals(1, service.list(7, 1).items.size)
	}

	@Test
	fun `treats a deleted fork as a fork`() {
		given(projects.get(7)).willReturn(project())
		val orphan = pull().copy(head = GitHubRef("fix", "ghost:fix", null))
		given(client.listOpen("acme", "factory", 1)).willReturn(GitHubPage(listOf(orphan), hasNext = false))

		assertEquals("ghost:fix", service.list(7, 1).items.single().headRef)
	}

	@Test
	fun `gets a pull request with its counts, and an empty body as null`() {
		given(projects.get(7)).willReturn(project())
		given(client.get("acme", "factory", 42)).willReturn(pull(body = "  ", commits = 3, additions = 120, deletions = 14, changedFiles = 7))

		val pull = service.get(7, 42)

		assertNull(pull.body)
		assertEquals(3, pull.commits)
		assertEquals(120, pull.additions)
		assertEquals(14, pull.deletions)
		assertEquals(7, pull.changedFiles)
		assertEquals("open", pull.state)
	}

	@Test
	fun `tells merged from closed`() {
		given(projects.get(7)).willReturn(project())
		given(client.get("acme", "factory", 1)).willReturn(pull(state = "closed", mergedAt = DONE, closedAt = DONE))
		given(client.get("acme", "factory", 2)).willReturn(pull(state = "closed", closedAt = DONE))

		assertEquals("merged", service.get(7, 1).state)
		val closed = service.get(7, 2)
		assertEquals("closed", closed.state)
		assertEquals(DONE, closed.closedAt)
		assertNull(closed.mergedAt)
	}

	@Test
	fun `lists changed files with renames and missing patches`() {
		given(projects.get(7)).willReturn(project())
		val renamed = GitHubFile("b.ts", "a.ts", "renamed", 1, 1, "@@ -1 +1 @@\n-a\n+b", "https://github.com/acme/factory/blob/x/b.ts")
		val binary = GitHubFile("logo.png", null, "added", 0, 0, null, "https://github.com/acme/factory/blob/x/logo.png")
		given(client.listFiles("acme", "factory", 42, 2)).willReturn(GitHubPage(listOf(renamed, binary), hasNext = false))

		val page = service.files(7, 42, 2)

		assertEquals(30, page.size)
		assertEquals("a.ts", page.items[0].previousFilename)
		assertEquals("https://github.com/acme/factory/blob/x/b.ts", page.items[0].htmlUrl)
		assertNull(page.items[1].patch)
	}

	@Test
	fun `reports an unknown project`() {
		given(projects.get(404)).willThrow(ProjectNotFoundException(404))

		assertThrows<ProjectNotFoundException> { service.list(404, 1) }
		assertThrows<ProjectNotFoundException> { service.get(404, 1) }
	}

	@Test
	fun `tells a missing pull request from a missing repository`() {
		given(projects.get(7)).willReturn(project())
		given(client.get("acme", "factory", 99)).willThrow(GitHubNotFoundException("acme/factory pull 99"))
		given(client.listFiles("acme", "factory", 99, 1)).willThrow(GitHubNotFoundException("acme/factory pull 99 files"))

		given(checker.check("acme", "factory")).willReturn(RepositoryCheck.FOUND)
		assertThrows<PullRequestNotFoundException> { service.get(7, 99) }
		assertThrows<PullRequestNotFoundException> { service.files(7, 99, 1) }

		given(checker.check("acme", "factory")).willReturn(RepositoryCheck.NOT_FOUND)
		assertThrows<PullRequestRepositoryNotFoundException> { service.get(7, 99) }
	}

	@Test
	fun `reports a missing repository from the list without another check`() {
		given(projects.get(7)).willReturn(project())
		given(client.listOpen("acme", "factory", 1)).willThrow(GitHubNotFoundException("acme/factory pulls"))

		assertThrows<PullRequestRepositoryNotFoundException> { service.list(7, 1) }
		then(checker).shouldHaveNoInteractions()
	}

	@Test
	fun `rejects a page or number below 1 before calling GitHub`() {
		assertEquals("page", assertThrows<InvalidPullRequestParameterException> { service.list(7, 0) }.name)
		assertEquals("number", assertThrows<InvalidPullRequestParameterException> { service.get(7, 0) }.name)
		assertEquals("page", assertThrows<InvalidPullRequestParameterException> { service.files(7, 1, -1) }.name)
		then(client).shouldHaveNoInteractions()
	}

	private fun project(archived: Boolean = false, verified: Boolean = true) = Project().apply {
		id = 7
		name = "Factory"
		nameKey = "factory"
		repositoryUrl = "https://github.com/acme/factory"
		status = ProjectStatus.ACTIVE
		statusOrder = status.ordinal
		this.archived = archived
		repositoryVerified = verified
		createdAt = CREATED
		updatedAt = CREATED
	}

	private fun pull(
		state: String = "open",
		body: String? = "Lists projects.",
		mergedAt: Instant? = null,
		closedAt: Instant? = null,
		commits: Int? = null,
		additions: Int? = null,
		deletions: Int? = null,
		changedFiles: Int? = null,
	) = GitHubPull(
		number = 42,
		title = "Add the projects list",
		state = state,
		draft = false,
		user = GitHubUser("octocat", "https://avatars.githubusercontent.com/u/1?v=4"),
		head = GitHubRef("feat/list", "acme:feat/list", GitHubRefRepo("acme/factory")),
		base = GitHubRef("main", "acme:main", GitHubRefRepo("acme/factory")),
		body = body,
		createdAt = CREATED,
		updatedAt = CREATED,
		mergedAt = mergedAt,
		closedAt = closedAt,
		htmlUrl = "https://github.com/acme/factory/pull/42",
		commits = commits,
		additions = additions,
		deletions = deletions,
		changedFiles = changedFiles,
	)

	private fun forkPull() = pull().copy(
		number = 41,
		draft = true,
		user = null,
		head = GitHubRef("fix", "someone:fix", GitHubRefRepo("someone/factory")),
	)

	companion object {
		private val CREATED: Instant = Instant.parse("2026-11-04T10:15:30Z")
		private val DONE: Instant = Instant.parse("2026-11-05T09:00:00Z")
	}
}
