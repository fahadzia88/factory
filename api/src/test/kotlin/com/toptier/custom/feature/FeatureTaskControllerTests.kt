package com.toptier.custom.feature

import com.toptier.custom.checkout.exception.GitNotConfiguredException
import com.toptier.custom.feature.TaskFixtures.failingChecks
import com.toptier.custom.feature.TaskFixtures.pull
import com.toptier.custom.feature.TaskFixtures.task
import com.toptier.custom.feature.controller.FeatureExceptionHandler
import com.toptier.custom.feature.controller.FeatureTaskController
import com.toptier.custom.feature.exception.FeatureNotFoundException
import com.toptier.custom.feature.exception.FeatureWorktreeMissingException
import com.toptier.custom.feature.exception.InvalidTaskException
import com.toptier.custom.feature.exception.PreviousTaskUnfinishedException
import com.toptier.custom.feature.exception.SpecsNotApprovedException
import com.toptier.custom.feature.exception.TaskExistsException
import com.toptier.custom.feature.exception.TaskOutOfOrderException
import com.toptier.custom.feature.service.TaskStarter
import com.toptier.custom.feature.exception.TaskNotFoundException
import com.toptier.custom.feature.service.FeatureTasks
import com.toptier.custom.feature.service.FeatureWorktree
import com.toptier.custom.feature.service.PullProblems
import com.toptier.custom.feature.service.TaskView
import com.toptier.custom.github.exception.GitHubRefusedException
import com.toptier.custom.github.exception.GitHubTimeoutException
import com.toptier.custom.security.config.SecurityConfig
import com.toptier.custom.security.config.SecurityProperties
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.http.MediaType
import java.net.SocketTimeoutException

@WebMvcTest(FeatureTaskController::class)
@Import(SecurityConfig::class, FeatureExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class FeatureTaskControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@MockitoBean
	lateinit var featureTasks: FeatureTasks

	@MockitoBean
	lateinit var taskStarter: TaskStarter

	@Test
	fun `rejects requests without a token`() {
		mockMvc.get(BASE).andExpect { status { isUnauthorized() } }
		mockMvc.get("$BASE/1").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `lists tasks with their pull requests and errors`() {
		given(featureTasks.list(12, 21)).willReturn(
			listOf(
				TaskView(task(0, "feat/006-x/00-docs", "main"), FeatureWorktree(9, "/repos/12.worktrees/feat__006-x__00-docs", false), null, null, false),
				TaskView(
					task(1, "feat/006-x/01-api-a", "feat/006-x/00-docs", pullNumber = 11).apply { specPath = "feature/006-x/tasks/01-api-a/spec.md" },
					null, pull(11, checks = failingChecks()), null, true,
				),
				TaskView(
					task(2, "feat/006-x/02-app-b", "feat/006-x/01-api-a", pullNumber = 12), null, null,
					PullProblems.describe(GitHubTimeoutException("x", SocketTimeoutException())), false,
				),
			),
		)

		mockMvc.get(BASE) { auth() }.andExpect {
			status { isOk() }
			jsonPath("$[0].number") { value(0) }
			jsonPath("$[0].layer") { value("docs") }
			jsonPath("$[0].specPath") { value(null as Any?) }
			jsonPath("$[0].worktree.path") { value("/repos/12.worktrees/feat__006-x__00-docs") }
			jsonPath("$[0].pull") { value(null as Any?) }
			jsonPath("$[1].specPath") { value("feature/006-x/tasks/01-api-a/spec.md") }
			jsonPath("$[1].pull.number") { value(11) }
			jsonPath("$[1].pull.state") { value("open") }
			jsonPath("$[1].pull.checks.state") { value("failure") }
			jsonPath("$[1].pull.checks.runs[0].name") { value("test") }
			jsonPath("$[1].pull.checks.runs[0].summary") { value("FooTest") }
			jsonPath("$[1].needsRestack") { value(true) }
			jsonPath("$[2].pull") { value(null as Any?) }
			jsonPath("$[2].pullError") { value("GitHub didn't answer in time. Try again.") }
		}
	}

	@Test
	fun `errors map to problems`() {
		given(featureTasks.get(12, 21, 9)).willThrow(TaskNotFoundException())
		given(featureTasks.get(12, 22, 1)).willThrow(FeatureNotFoundException())
		given(featureTasks.get(12, 21, 1)).willThrow(GitHubRefusedException(403, "x"))
		given(featureTasks.get(12, 21, 2)).willThrow(GitHubTimeoutException("x", SocketTimeoutException()))
		given(featureTasks.get(12, 21, 3)).willThrow(GitNotConfiguredException())

		mockMvc.get("$BASE/9") { auth() }.andExpect { problem(404, "task_not_found") }
		mockMvc.get("/api/projects/12/features/22/tasks/1") { auth() }.andExpect { problem(404, "feature_not_found") }
		mockMvc.get("$BASE/1") { auth() }.andExpect { problem(502, "github_error") }
		mockMvc.get("$BASE/2") { auth() }.andExpect { problem(504, "github_timeout") }
		mockMvc.get("$BASE/3") { auth() }.andExpect { problem(503, "git_not_configured") }
		mockMvc.get("$BASE/abc") { auth() }.andExpect { problem(400, "invalid_request") }
	}

	@Test
	fun `start answers 201 with the task`() {
		given(taskStarter.start(12, 21, "01-api-a")).willReturn(
			TaskView(task(1, "feat/006-x/01-api-a", "feat/006-x/00-docs"), FeatureWorktree(10, "/repos/12.worktrees/feat__006-x__01-api-a", false), null, null, false),
		)

		mockMvc.post(BASE) { json("""{"dir":"01-api-a"}""") }.andExpect { status { isUnauthorized() } }
		mockMvc.post(BASE) { auth(); json("""{"dir":"01-api-a"}""") }.andExpect {
			status { isCreated() }
			jsonPath("$.number") { value(1) }
			jsonPath("$.branch") { value("feat/006-x/01-api-a") }
			jsonPath("$.worktree.id") { value(10) }
		}
	}

	@Test
	fun `start refusals map to 400 and 409`() {
		given(taskStarter.start(12, 21, "bad")).willThrow(InvalidTaskException("bad"))
		given(taskStarter.start(12, 21, "02-app-b")).willThrow(SpecsNotApprovedException(listOf("feature/006-x/tasks/02-app-b/spec.md")))
		given(taskStarter.start(12, 21, "01-api-a")).willThrow(TaskExistsException(1))
		given(taskStarter.start(12, 21, "03-agent-c")).willThrow(TaskOutOfOrderException(2))
		given(taskStarter.start(12, 21, "02-app-x")).willThrow(PreviousTaskUnfinishedException(1))
		given(taskStarter.start(12, 21, "09-api-z")).willThrow(FeatureWorktreeMissingException("feat/006-x/00-docs"))

		mockMvc.post(BASE) { auth(); json("""{"dir":"bad"}""") }.andExpect { problem(400, "invalid_task") }
		mockMvc.post(BASE) { auth(); json("""{"dir":"02-app-b"}""") }.andExpect {
			problem(409, "specs_not_approved")
			jsonPath("$.unapproved[0]") { value("feature/006-x/tasks/02-app-b/spec.md") }
		}
		mockMvc.post(BASE) { auth(); json("""{"dir":"01-api-a"}""") }.andExpect { problem(409, "task_exists") }
		mockMvc.post(BASE) { auth(); json("""{"dir":"03-agent-c"}""") }.andExpect {
			problem(409, "task_out_of_order")
			jsonPath("$.expected") { value(2) }
		}
		mockMvc.post(BASE) { auth(); json("""{"dir":"02-app-x"}""") }.andExpect { problem(409, "previous_task_unfinished") }
		mockMvc.post(BASE) { auth(); json("""{"dir":"09-api-z"}""") }.andExpect { problem(409, "worktree_missing") }
		mockMvc.post(BASE) { auth(); json("{not json") }.andExpect { problem(400, "invalid_request") }
	}

	private fun MockHttpServletRequestDsl.json(body: String) {
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun MockMvcResultMatchersDsl.problem(status: Int, code: String) {
		status { isEqualTo(status) }
		jsonPath("$.code") { value(code) }
	}

	private fun MockHttpServletRequestDsl.auth() {
		header("Authorization", "Bearer test-token")
	}

	private companion object {
		const val BASE = "/api/projects/12/features/21/tasks"
	}
}
