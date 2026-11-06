package com.toptier.custom.checkout

import com.toptier.custom.checkout.controller.CheckoutController
import com.toptier.custom.checkout.controller.CheckoutExceptionHandler
import com.toptier.custom.checkout.controller.CheckoutLookupController
import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckout
import com.toptier.custom.checkout.domain.RepositoryWorktree
import com.toptier.custom.checkout.exception.CheckoutExistsException
import com.toptier.custom.checkout.exception.CheckoutNotFoundException
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.checkout.exception.CheckoutProjectArchivedException
import com.toptier.custom.checkout.exception.GitCommandException
import com.toptier.custom.checkout.exception.GitFailure
import com.toptier.custom.checkout.exception.GitNotConfiguredException
import com.toptier.custom.checkout.exception.GitRemoteException
import com.toptier.custom.checkout.exception.GitTimeoutException
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.CheckoutView
import com.toptier.custom.checkout.service.HeadCommit
import com.toptier.custom.checkout.service.UnsavedWork
import com.toptier.custom.checkout.service.WorktreeView
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.security.config.SecurityConfig
import com.toptier.custom.security.config.SecurityProperties
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant

@WebMvcTest(CheckoutController::class, CheckoutLookupController::class)
@Import(SecurityConfig::class, CheckoutExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class CheckoutControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@MockitoBean
	lateinit var checkoutService: CheckoutService

	@Test
	fun `rejects requests without a token`() {
		mockMvc.get("/api/projects/12/checkout").andExpect { status { isUnauthorized() } }
		mockMvc.post("/api/projects/12/checkout").andExpect { status { isUnauthorized() } }
		mockMvc.post("/api/projects/12/checkout/fetch").andExpect { status { isUnauthorized() } }
		mockMvc.get("/api/projects/12/checkout/unsaved").andExpect { status { isUnauthorized() } }
		mockMvc.get("/api/checkouts?repositoryUrl=https://github.com/acme/factory").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `unsaved lists each worktree's work`() {
		given(checkoutService.unsaved(12)).willReturn(
			listOf(UnsavedWork(9, "feat/003-x/01-api-y", listOf("api/Foo.kt"), listOf(HeadCommit("a1b2c3d", "WIP")))),
		)
		given(checkoutService.unsaved(13)).willReturn(emptyList())
		given(checkoutService.unsaved(99)).willThrow(ProjectNotFoundException(99))

		mockMvc.get("/api/projects/12/checkout/unsaved") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.length()") { value(1) }
			jsonPath("$[0].worktreeId") { value(9) }
			jsonPath("$[0].branch") { value("feat/003-x/01-api-y") }
			jsonPath("$[0].changedFiles[0]") { value("api/Foo.kt") }
			jsonPath("$[0].unpushedCommits[0].sha") { value("a1b2c3d") }
			jsonPath("$[0].unpushedCommits[0].subject") { value("WIP") }
		}
		mockMvc.get("/api/projects/13/checkout/unsaved") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.length()") { value(0) }
		}
		mockMvc.get("/api/projects/99/checkout/unsaved") { auth() }.andExpect { problem(404, "project_not_found") }
	}

	@Test
	fun `an archived project's checkout can't be started or fetched`() {
		given(checkoutService.start(12)).willThrow(CheckoutProjectArchivedException(12))
		given(checkoutService.fetch(12)).willThrow(CheckoutProjectArchivedException(12))

		mockMvc.post("/api/projects/12/checkout") { auth() }
			.andExpect { problem(409, "project_archived", "This project is archived. Restore it to use its repository checkout.") }
		mockMvc.post("/api/projects/12/checkout/fetch") { auth() }.andExpect { problem(409, "project_archived") }
	}

	@Test
	fun `the lookup finds a checkout by its exact repository URL`() {
		val url = "https://github.com/acme/factory"
		given(checkoutService.findByRepositoryUrl(url)).willReturn(CheckoutView(checkout(CheckoutState.READY), emptyList()))
		given(checkoutService.findByRepositoryUrl("https://github.com/acme/none")).willThrow(ProjectNotFoundException(-1))
		given(checkoutService.findByRepositoryUrl("https://github.com/acme/new")).willThrow(CheckoutNotFoundException())

		mockMvc.get("/api/checkouts") { auth(); param("repositoryUrl", url) }.andExpect {
			status { isOk() }
			jsonPath("$.id") { value(3) }
			jsonPath("$.projectId") { value(12) }
			jsonPath("$.state") { value("ready") }
		}
		mockMvc.get("/api/checkouts") { auth(); param("repositoryUrl", "https://github.com/acme/none") }
			.andExpect { problem(404, "project_not_found", "Project not found.") }
		mockMvc.get("/api/checkouts") { auth(); param("repositoryUrl", "https://github.com/acme/new") }
			.andExpect { problem(404, "checkout_not_found") }
		mockMvc.get("/api/checkouts") { auth() }.andExpect { problem(400, "invalid_request") }
	}

	@Test
	fun `get returns the checkout with the main worktree first`() {
		val checkout = checkout(CheckoutState.READY).apply {
			defaultBranch = "main"
			lastFetchedAt = FETCHED
		}
		val main = worktree(7, "main", "/repos/12", main = true, baseBranch = null)
		val feature = worktree(8, "feat/x", "/repos/12.worktrees/feat__x", main = false, baseBranch = "main")
		given(checkoutService.get(12)).willReturn(
			CheckoutView(checkout, listOf(WorktreeView(main, false, null), WorktreeView(feature, false, null))),
		)

		mockMvc.get("/api/projects/12/checkout") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.id") { value(3) }
			jsonPath("$.projectId") { value(12) }
			jsonPath("$.state") { value("ready") }
			jsonPath("$.error") { value(null) }
			jsonPath("$.path") { value("/repos/12") }
			jsonPath("$.defaultBranch") { value("main") }
			jsonPath("$.createdAt") { value("2026-11-05T10:00:00Z") }
			jsonPath("$.lastFetchedAt") { value("2026-11-05T10:15:00Z") }
			jsonPath("$.worktrees.length()") { value(2) }
			jsonPath("$.worktrees[0].id") { value(7) }
			jsonPath("$.worktrees[0].branch") { value("main") }
			jsonPath("$.worktrees[0].path") { value("/repos/12") }
			jsonPath("$.worktrees[0].main") { value(true) }
			jsonPath("$.worktrees[0].baseBranch") { value(null) }
			jsonPath("$.worktrees[0].missing") { value(false) }
			jsonPath("$.worktrees[0].head") { value(null) }
			jsonPath("$.worktrees[0].createdAt") { value("2026-11-05T10:00:00Z") }
			jsonPath("$.worktrees[1].branch") { value("feat/x") }
			jsonPath("$.worktrees[1].baseBranch") { value("main") }
		}
	}

	@Test
	fun `post answers 202 with the cloning checkout`() {
		given(checkoutService.start(12)).willReturn(CheckoutView(checkout(CheckoutState.CLONING), emptyList()))

		mockMvc.post("/api/projects/12/checkout") { auth() }.andExpect {
			status { isAccepted() }
			jsonPath("$.state") { value("cloning") }
			jsonPath("$.defaultBranch") { value(null) }
			jsonPath("$.lastFetchedAt") { value(null) }
			jsonPath("$.worktrees.length()") { value(0) }
		}
	}

	@Test
	fun `unknown projects, missing checkouts and bad ids`() {
		given(checkoutService.get(99)).willThrow(ProjectNotFoundException(99))
		given(checkoutService.get(12)).willThrow(CheckoutNotFoundException())

		mockMvc.get("/api/projects/99/checkout") { auth() }.andExpect { problem(404, "project_not_found", "Project not found.") }
		mockMvc.get("/api/projects/12/checkout") { auth() }
			.andExpect { problem(404, "checkout_not_found", "This project has no repository checkout.") }
		mockMvc.get("/api/projects/abc/checkout") { auth() }.andExpect { problem(400, "invalid_request") }
		mockMvc.post("/api/projects/abc/checkout") { auth() }.andExpect { problem(400, "invalid_request") }
	}

	@Test
	fun `an existing checkout and a missing token are refused`() {
		given(checkoutService.start(12)).willThrow(CheckoutExistsException())
		given(checkoutService.start(13)).willThrow(GitNotConfiguredException())

		mockMvc.post("/api/projects/12/checkout") { auth() }
			.andExpect { problem(409, "checkout_exists", "This project already has a checkout.") }
		mockMvc.post("/api/projects/13/checkout") { auth() }
			.andExpect { problem(503, "git_not_configured", "The API has no git token configured. Set API_GIT_TOKEN in the root .env.") }
	}

	@Test
	fun `git failures map to 502, 504 and 500 without git output`() {
		given(checkoutService.get(12)).willThrow(GitRemoteException(GitFailure.AUTH))
		given(checkoutService.get(13)).willThrow(GitTimeoutException(Duration.ofMinutes(2)))
		given(checkoutService.get(14)).willThrow(GitCommandException("status", 128, "fatal: secret stderr from https://github.com/a/b"))

		mockMvc.get("/api/projects/12/checkout") { auth() }.andExpect {
			problem(
				502, "git_remote_error",
				"GitHub rejected the git token. Check that API_GIT_TOKEN has Contents: read and write on this repository.",
			)
		}
		mockMvc.get("/api/projects/13/checkout") { auth() }
			.andExpect { problem(504, "git_timeout", "GitHub didn't answer within 2 minutes. Try again.") }
		mockMvc.get("/api/projects/14/checkout") { auth() }.andExpect {
			problem(500, "git_error", "A git command failed on the server.")
			content { string(not(containsString("secret stderr"))) }
		}
	}

	@Test
	fun `fetch answers 200 with the checkout`() {
		val checkout = checkout(CheckoutState.READY).apply {
			defaultBranch = "main"
			lastFetchedAt = FETCHED
		}
		val main = worktree(7, "main", "/repos/12", main = true, baseBranch = null)
		given(checkoutService.fetch(12)).willReturn(CheckoutView(checkout, listOf(WorktreeView(main, false, null))))

		mockMvc.post("/api/projects/12/checkout/fetch") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.id") { value(3) }
			jsonPath("$.state") { value("ready") }
			jsonPath("$.defaultBranch") { value("main") }
			jsonPath("$.lastFetchedAt") { value("2026-11-05T10:15:00Z") }
			jsonPath("$.worktrees.length()") { value(1) }
			jsonPath("$.worktrees[0].main") { value(true) }
		}
	}

	@Test
	fun `fetch errors map to their codes`() {
		given(checkoutService.fetch(11)).willThrow(CheckoutNotReadyException())
		given(checkoutService.fetch(12)).willThrow(CheckoutNotFoundException())
		given(checkoutService.fetch(13)).willThrow(GitRemoteException(GitFailure.AUTH))
		given(checkoutService.fetch(14)).willThrow(GitRemoteException(GitFailure.UNREACHABLE))
		given(checkoutService.fetch(15)).willThrow(GitTimeoutException(Duration.ofMinutes(2)))
		given(checkoutService.fetch(16)).willThrow(GitNotConfiguredException())

		mockMvc.post("/api/projects/11/checkout/fetch") { auth() }
			.andExpect { problem(409, "checkout_not_ready", "The checkout isn't ready yet.") }
		mockMvc.post("/api/projects/12/checkout/fetch") { auth() }
			.andExpect { problem(404, "checkout_not_found", "This project has no repository checkout.") }
		mockMvc.post("/api/projects/13/checkout/fetch") { auth() }.andExpect {
			problem(
				502, "git_remote_error",
				"GitHub rejected the git token. Check that API_GIT_TOKEN has Contents: read and write on this repository.",
			)
		}
		mockMvc.post("/api/projects/14/checkout/fetch") { auth() }
			.andExpect { problem(502, "git_remote_error", "GitHub couldn't be reached. Try again.") }
		mockMvc.post("/api/projects/15/checkout/fetch") { auth() }
			.andExpect { problem(504, "git_timeout", "GitHub didn't answer within 2 minutes. Try again.") }
		mockMvc.post("/api/projects/16/checkout/fetch") { auth() }
			.andExpect { problem(503, "git_not_configured", "The API has no git token configured. Set API_GIT_TOKEN in the root .env.") }
		mockMvc.post("/api/projects/abc/checkout/fetch") { auth() }.andExpect { problem(400, "invalid_request") }
	}

	private fun MockMvcResultMatchersDsl.problem(status: Int, code: String, detail: String? = null) {
		status { isEqualTo(status) }
		content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		jsonPath("$.code") { value(code) }
		if (detail != null) jsonPath("$.detail") { value(detail) }
	}

	private fun MockHttpServletRequestDsl.auth() {
		header("Authorization", "Bearer test-token")
	}

	private fun checkout(state: CheckoutState) = RepositoryCheckout().apply {
		id = 3
		projectId = 12
		path = "/repos/12"
		this.state = state
		createdAt = CREATED
	}

	private fun worktree(id: Long, branch: String, path: String, main: Boolean, baseBranch: String?) = RepositoryWorktree().apply {
		this.id = id
		checkoutId = 3
		this.branch = branch
		this.path = path
		this.main = main
		this.baseBranch = baseBranch
		createdAt = CREATED
	}

	companion object {
		val CREATED: Instant = Instant.parse("2026-11-05T10:00:00Z")
		val FETCHED: Instant = Instant.parse("2026-11-05T10:15:00Z")
	}
}
