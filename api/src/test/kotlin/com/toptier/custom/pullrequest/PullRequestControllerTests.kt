package com.toptier.custom.pullrequest

import com.toptier.custom.github.exception.GitHubNotConfiguredException
import com.toptier.custom.github.exception.GitHubRateLimitedException
import com.toptier.custom.github.exception.GitHubRefusedException
import com.toptier.custom.github.exception.GitHubTimeoutException
import com.toptier.custom.github.exception.GitHubUnreachableException
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.pullrequest.controller.PullRequestController
import com.toptier.custom.pullrequest.controller.PullRequestExceptionHandler
import com.toptier.custom.pullrequest.dto.PageResponse
import com.toptier.custom.pullrequest.dto.PullRequestAuthor
import com.toptier.custom.pullrequest.dto.PullRequestFileResponse
import com.toptier.custom.pullrequest.dto.PullRequestResponse
import com.toptier.custom.pullrequest.dto.PullRequestSummaryResponse
import com.toptier.custom.pullrequest.exception.PullRequestNotFoundException
import com.toptier.custom.pullrequest.exception.PullRequestRepositoryNotFoundException
import com.toptier.custom.pullrequest.service.PullRequestService
import com.toptier.custom.security.config.SecurityConfig
import com.toptier.custom.security.config.SecurityProperties
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.net.SocketTimeoutException
import java.time.Instant

@WebMvcTest(PullRequestController::class)
@Import(SecurityConfig::class, PullRequestExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class PullRequestControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@MockitoBean
	lateinit var service: PullRequestService

	@Test
	fun `rejects requests without a token`() {
		mockMvc.get("/api/projects/7/pulls").andExpect { status { isUnauthorized() } }
		mockMvc.get("/api/projects/7/pulls/42").andExpect { status { isUnauthorized() } }
		mockMvc.get("/api/projects/7/pulls/42/files").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `lists open pull requests`() {
		given(service.list(7, null)).willReturn(PageResponse(listOf(summary()), 1, 20, true))

		mockMvc.get("/api/projects/7/pulls") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.page") { value(1) }
			jsonPath("$.size") { value(20) }
			jsonPath("$.hasNext") { value(true) }
			jsonPath("$.items[0].number") { value(42) }
			jsonPath("$.items[0].title") { value("Add the projects list") }
			jsonPath("$.items[0].state") { value("open") }
			jsonPath("$.items[0].draft") { value(false) }
			jsonPath("$.items[0].author.login") { value("octocat") }
			jsonPath("$.items[0].author.avatarUrl") { value(AVATAR) }
			jsonPath("$.items[0].headRef") { value("feat/list") }
			jsonPath("$.items[0].baseRef") { value("main") }
			jsonPath("$.items[0].createdAt") { value("2026-11-04T10:15:30Z") }
			jsonPath("$.items[0].updatedAt") { value("2026-11-05T08:00:00Z") }
			jsonPath("$.items[0].htmlUrl") { value("https://github.com/acme/factory/pull/42") }
		}
	}

	@Test
	fun `passes the page through`() {
		given(service.list(7, 3)).willReturn(PageResponse(emptyList(), 3, 20, false))
		given(service.files(7, 42, 2)).willReturn(PageResponse(emptyList(), 2, 30, false))

		mockMvc.get("/api/projects/7/pulls?page=3") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.page") { value(3) }
			jsonPath("$.items") { isEmpty() }
		}
		mockMvc.get("/api/projects/7/pulls/42/files?page=2") { auth() }.andExpect { jsonPath("$.page") { value(2) } }
	}

	@Test
	fun `gets one pull request`() {
		given(service.get(7, 42)).willReturn(detail())

		mockMvc.get("/api/projects/7/pulls/42") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.number") { value(42) }
			jsonPath("$.state") { value("merged") }
			jsonPath("$.author") { value(null as Any?) }
			jsonPath("$.body") { value("## Summary") }
			jsonPath("$.commits") { value(3) }
			jsonPath("$.additions") { value(120) }
			jsonPath("$.deletions") { value(14) }
			jsonPath("$.changedFiles") { value(7) }
			jsonPath("$.mergedAt") { value("2026-11-05T09:00:00Z") }
			jsonPath("$.closedAt") { value("2026-11-05T09:00:00Z") }
		}
	}

	@Test
	fun `lists changed files`() {
		val renamed = PullRequestFileResponse("b.ts", "a.ts", "renamed", 1, 1, "@@ -1 +1 @@", "https://github.com/acme/factory/blob/x/b.ts")
		val binary = PullRequestFileResponse("logo.png", null, "added", 0, 0, null, "https://github.com/acme/factory/blob/x/logo.png")
		given(service.files(7, 42, null)).willReturn(PageResponse(listOf(renamed, binary), 1, 30, false))

		mockMvc.get("/api/projects/7/pulls/42/files") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.size") { value(30) }
			jsonPath("$.items[0].filename") { value("b.ts") }
			jsonPath("$.items[0].previousFilename") { value("a.ts") }
			jsonPath("$.items[0].status") { value("renamed") }
			jsonPath("$.items[0].additions") { value(1) }
			jsonPath("$.items[0].patch") { value("@@ -1 +1 @@") }
			jsonPath("$.items[0].htmlUrl") { value("https://github.com/acme/factory/blob/x/b.ts") }
			jsonPath("$.items[1].previousFilename") { value(null as Any?) }
			jsonPath("$.items[1].patch") { value(null as Any?) }
		}
	}

	@Test
	fun `answers not found with a code saying what is missing`() {
		given(service.get(404, 1)).willThrow(ProjectNotFoundException(404))
		given(service.get(7, 99)).willThrow(PullRequestNotFoundException(7, 99))
		given(service.list(8, null)).willThrow(PullRequestRepositoryNotFoundException(8))

		expectProblem("/api/projects/404/pulls/1", 404, "project_not_found", "Project not found.")
		expectProblem("/api/projects/7/pulls/99", 404, "pull_request_not_found", "Pull request not found.")
		expectProblem(
			"/api/projects/8/pulls",
			404,
			"repository_not_found",
			"GitHub couldn't find this repository, or the API's token can't see it.",
		)
	}

	@Test
	fun `answers GitHub failures with their codes`() {
		given(service.list(1, null)).willThrow(GitHubRefusedException(403, "x"))
		given(service.list(2, null)).willThrow(GitHubRefusedException(401, "x"))
		given(service.list(3, null)).willThrow(GitHubUnreachableException("x", RuntimeException()))
		given(service.list(4, null)).willThrow(GitHubRateLimitedException("x"))
		given(service.list(5, null)).willThrow(GitHubNotConfiguredException())
		given(service.list(6, null)).willThrow(GitHubTimeoutException("x", SocketTimeoutException()))

		expectProblem(
			"/api/projects/1/pulls",
			502,
			"github_error",
			"GitHub refused the request. The API's token may need \"Pull requests: read\" on this repository.",
		)
		expectProblem("/api/projects/2/pulls", 502, "github_error", "GitHub couldn't be reached. Try again.")
		expectProblem("/api/projects/3/pulls", 502, "github_error", "GitHub couldn't be reached. Try again.")
		expectProblem("/api/projects/4/pulls", 502, "github_rate_limited", "GitHub's rate limit was reached. Try again in a few minutes.")
		expectProblem("/api/projects/5/pulls", 503, "github_not_configured", "The API has no GitHub token configured.")
		expectProblem("/api/projects/6/pulls", 504, "github_timeout", "GitHub didn't answer in time. Try again.")
	}

	@Test
	fun `rejects a page or number that isn't a number`() {
		expectProblem("/api/projects/7/pulls?page=abc", 400, "invalid_request", "Invalid value for page.")
		expectProblem("/api/projects/7/pulls/abc", 400, "invalid_request", "Invalid value for number.")
	}

	private fun expectProblem(path: String, status: Int, code: String, detail: String) {
		mockMvc.get(path) { auth() }.andExpect {
			status { isEqualTo(status) }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.code") { value(code) }
			jsonPath("$.detail") { value(detail) }
		}
	}

	private fun MockHttpServletRequestDsl.auth() {
		header("Authorization", "Bearer test-token")
	}

	private fun summary() = PullRequestSummaryResponse(
		number = 42,
		title = "Add the projects list",
		state = "open",
		draft = false,
		author = PullRequestAuthor("octocat", AVATAR),
		headRef = "feat/list",
		baseRef = "main",
		createdAt = Instant.parse("2026-11-04T10:15:30Z"),
		updatedAt = Instant.parse("2026-11-05T08:00:00Z"),
		htmlUrl = "https://github.com/acme/factory/pull/42",
	)

	private fun detail() = PullRequestResponse(
		number = 42,
		title = "Add the projects list",
		state = "merged",
		draft = false,
		author = null,
		headRef = "feat/list",
		baseRef = "main",
		createdAt = Instant.parse("2026-11-04T10:15:30Z"),
		updatedAt = Instant.parse("2026-11-05T09:00:00Z"),
		htmlUrl = "https://github.com/acme/factory/pull/42",
		body = "## Summary",
		commits = 3,
		additions = 120,
		deletions = 14,
		changedFiles = 7,
		mergedAt = Instant.parse("2026-11-05T09:00:00Z"),
		closedAt = Instant.parse("2026-11-05T09:00:00Z"),
	)

	companion object {
		private const val AVATAR = "https://avatars.githubusercontent.com/u/1?v=4"
	}
}
