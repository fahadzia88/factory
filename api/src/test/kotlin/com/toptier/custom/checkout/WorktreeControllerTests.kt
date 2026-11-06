package com.toptier.custom.checkout

import com.toptier.custom.checkout.controller.CheckoutExceptionHandler
import com.toptier.custom.checkout.controller.WorktreeController
import com.toptier.custom.checkout.domain.RepositoryWorktree
import com.toptier.custom.checkout.exception.BaseNotFoundException
import com.toptier.custom.checkout.exception.BranchCheckedOutException
import com.toptier.custom.checkout.exception.CheckoutNotFoundException
import com.toptier.custom.checkout.exception.GitNotConfiguredException
import com.toptier.custom.checkout.exception.GitTimeoutException
import com.toptier.custom.checkout.exception.InvalidBranchException
import com.toptier.custom.checkout.exception.MainWorktreeException
import com.toptier.custom.checkout.exception.WorktreeNotFoundException
import com.toptier.custom.checkout.exception.WorktreeUnsavedException
import com.toptier.custom.checkout.service.HeadCommit
import com.toptier.custom.checkout.service.UnsavedWork
import com.toptier.custom.checkout.service.WorktreeService
import com.toptier.custom.checkout.service.WorktreeView
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.security.config.SecurityConfig
import com.toptier.custom.security.config.SecurityProperties
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant

@WebMvcTest(WorktreeController::class)
@Import(SecurityConfig::class, CheckoutExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class WorktreeControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@MockitoBean
	lateinit var worktreeService: WorktreeService

	@Test
	fun `rejects requests without a token`() {
		mockMvc.post(BASE) { json("""{"branch":"feat/x"}""") }.andExpect { status { isUnauthorized() } }
		mockMvc.delete("$BASE/8").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `add answers 201 with the worktree`() {
		given(worktreeService.add(12, "feat/x", "develop")).willReturn(view(baseBranch = "develop"))
		given(worktreeService.add(12, "feat/y", null)).willReturn(view(baseBranch = "main"))

		mockMvc.post(BASE) { auth(); json("""{"branch":"feat/x","base":"develop"}""") }.andExpect {
			status { isCreated() }
			jsonPath("$.id") { value(8) }
			jsonPath("$.branch") { value("feat/x") }
			jsonPath("$.path") { value("/repos/12.worktrees/feat__x") }
			jsonPath("$.main") { value(false) }
			jsonPath("$.baseBranch") { value("develop") }
			jsonPath("$.missing") { value(false) }
			jsonPath("$.head.sha") { value("a1b2c3d") }
			jsonPath("$.head.subject") { value("Initial commit") }
			jsonPath("$.createdAt") { value("2026-11-05T10:00:00Z") }
		}
		mockMvc.post(BASE) { auth(); json("""{"branch":"feat/y"}""") }.andExpect {
			status { isCreated() }
			jsonPath("$.baseBranch") { value("main") }
		}
	}

	@Test
	fun `add refusals map to 400 and 409`() {
		given(worktreeService.add(12, "bad..name", null)).willThrow(InvalidBranchException("bad..name"))
		given(worktreeService.add(12, "feat/a", "nope")).willThrow(BaseNotFoundException("nope"))
		given(worktreeService.add(12, "main", null)).willThrow(BranchCheckedOutException.of("main"))

		mockMvc.post(BASE) { auth(); json("""{"branch":"bad..name"}""") }
			.andExpect { problem(400, "invalid_branch", "\"bad..name\" isn't a valid branch name.") }
		mockMvc.post(BASE) { auth(); json("""{"branch":"feat/a","base":"nope"}""") }
			.andExpect { problem(400, "base_not_found", "Base branch nope doesn't exist on GitHub or in the checkout.") }
		mockMvc.post(BASE) { auth(); json("""{"branch":"main"}""") }
			.andExpect { problem(409, "branch_checked_out", "main is already checked out in a worktree.") }
		mockMvc.post(BASE) { auth(); json("{not json") }.andExpect { problem(400, "invalid_request") }
	}

	@Test
	fun `add git failures map to 504 and 503`() {
		given(worktreeService.add(12, "feat/t", null)).willThrow(GitTimeoutException(Duration.ofMinutes(2)))
		given(worktreeService.add(12, "feat/n", null)).willThrow(GitNotConfiguredException())

		mockMvc.post(BASE) { auth(); json("""{"branch":"feat/t"}""") }.andExpect { problem(504, "git_timeout") }
		mockMvc.post(BASE) { auth(); json("""{"branch":"feat/n"}""") }.andExpect { problem(503, "git_not_configured") }
	}

	@Test
	fun `remove answers 204 and passes force`() {
		mockMvc.delete("$BASE/8") { auth() }.andExpect { status { isNoContent() } }
		verify(worktreeService).remove(12, 8, false)

		mockMvc.delete("$BASE/9?force=true") { auth() }.andExpect { status { isNoContent() } }
		verify(worktreeService).remove(12, 9, true)
	}

	@Test
	fun `remove with unsaved work answers 409 with what would be lost`() {
		val unsaved = UnsavedWork(8, "feat/x", listOf("api/Foo.kt"), listOf(HeadCommit("a1b2c3d", "WIP")))
		willThrow(WorktreeUnsavedException(unsaved)).given(worktreeService).remove(12, 8, false)

		mockMvc.delete("$BASE/8") { auth() }.andExpect {
			problem(
				409, "worktree_unsaved",
				"This worktree has changes that aren't on GitHub. Remove it with force to discard them.",
			)
			jsonPath("$.unsaved.worktreeId") { value(8) }
			jsonPath("$.unsaved.branch") { value("feat/x") }
			jsonPath("$.unsaved.changedFiles[0]") { value("api/Foo.kt") }
			jsonPath("$.unsaved.unpushedCommits[0].sha") { value("a1b2c3d") }
			jsonPath("$.unsaved.unpushedCommits[0].subject") { value("WIP") }
		}
	}

	@Test
	fun `removing main answers 409`() {
		willThrow(MainWorktreeException()).given(worktreeService).remove(12, 7, false)

		mockMvc.delete("$BASE/7") { auth() }
			.andExpect { problem(409, "main_worktree", "The main worktree can't be removed.") }
	}

	@Test
	fun `unknown projects, checkouts and worktrees, and bad ids`() {
		willThrow(ProjectNotFoundException(99)).given(worktreeService).remove(99, 8, false)
		willThrow(CheckoutNotFoundException()).given(worktreeService).remove(13, 8, false)
		willThrow(WorktreeNotFoundException()).given(worktreeService).remove(12, 404, false)
		given(worktreeService.add(99, "feat/x", null)).willThrow(ProjectNotFoundException(99))

		mockMvc.delete("/api/projects/99/checkout/worktrees/8") { auth() }.andExpect { problem(404, "project_not_found") }
		mockMvc.delete("/api/projects/13/checkout/worktrees/8") { auth() }.andExpect { problem(404, "checkout_not_found") }
		mockMvc.delete("$BASE/404") { auth() }.andExpect { problem(404, "worktree_not_found", "Worktree not found.") }
		mockMvc.post("/api/projects/99/checkout/worktrees") { auth(); json("""{"branch":"feat/x"}""") }
			.andExpect { problem(404, "project_not_found") }
		mockMvc.delete("$BASE/abc") { auth() }.andExpect { problem(400, "invalid_request") }
		mockMvc.post("/api/projects/abc/checkout/worktrees") { auth(); json("""{"branch":"feat/x"}""") }
			.andExpect { problem(400, "invalid_request") }
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

	private fun MockHttpServletRequestDsl.json(body: String) {
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun view(baseBranch: String?) = WorktreeView(
		RepositoryWorktree().apply {
			id = 8
			checkoutId = 3
			branch = "feat/x"
			path = "/repos/12.worktrees/feat__x"
			main = false
			this.baseBranch = baseBranch
			createdAt = CREATED
		},
		false,
		HeadCommit("a1b2c3d", "Initial commit"),
	)

	companion object {
		const val BASE = "/api/projects/12/checkout/worktrees"
		val CREATED: Instant = Instant.parse("2026-11-05T10:00:00Z")
	}
}
