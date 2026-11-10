package com.toptier.custom.feature

import com.toptier.custom.feature.controller.FeatureController
import com.toptier.custom.feature.controller.FeatureExceptionHandler
import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureStep
import com.toptier.custom.checkout.exception.CheckoutNotFoundException
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.checkout.exception.CheckoutProjectArchivedException
import com.toptier.custom.checkout.exception.GitCommandException
import com.toptier.custom.checkout.exception.GitFailure
import com.toptier.custom.checkout.exception.GitNotConfiguredException
import com.toptier.custom.checkout.exception.GitRemoteException
import com.toptier.custom.checkout.exception.GitTimeoutException
import com.toptier.custom.feature.exception.FeatureNotFoundException
import com.toptier.custom.feature.exception.FeatureProjectArchivedException
import com.toptier.custom.feature.exception.InvalidFeatureNameException
import com.toptier.custom.feature.exception.InvalidStatusException
import com.toptier.custom.feature.exception.InvalidStepException
import com.toptier.custom.feature.exception.InvalidStepTypeException
import com.toptier.custom.feature.exception.StepNotFoundException
import com.toptier.custom.feature.exception.WorkflowInvalidException
import com.toptier.custom.feature.service.FeatureStarter
import com.toptier.custom.feature.service.StartedFeature
import com.toptier.custom.feature.service.FeatureProgress
import com.toptier.custom.feature.service.StepChanges
import com.toptier.custom.feature.service.FeatureService
import com.toptier.custom.feature.service.FeatureView
import com.toptier.custom.feature.service.FeatureWorktree
import com.toptier.custom.feature.service.Workflow
import com.toptier.custom.feature.service.WorkflowStep
import com.toptier.custom.project.exception.ProjectNotFoundException
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
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Duration
import java.time.Instant

@WebMvcTest(FeatureController::class)
@Import(SecurityConfig::class, FeatureExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class FeatureControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@MockitoBean
	lateinit var featureService: FeatureService

	@MockitoBean
	lateinit var featureStarter: FeatureStarter

	@MockitoBean
	lateinit var featureProgress: FeatureProgress

	@Test
	fun `rejects requests without a token`() {
		mockMvc.get(BASE).andExpect { status { isUnauthorized() } }
		mockMvc.get("$BASE/21").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `list answers summary rows`() {
		given(featureService.list(12)).willReturn(listOf(feature()))

		mockMvc.get(BASE) { auth() }.andExpect {
			status { isOk() }
			jsonPath("$[0].id") { value(21) }
			jsonPath("$[0].number") { value(4) }
			jsonPath("$[0].code") { value("004-features") }
			jsonPath("$[0].name") { value("Features") }
			jsonPath("$[0].status") { value("requirements") }
			jsonPath("$[0].updatedAt") { value("2026-11-06T10:00:00Z") }
		}
	}

	@Test
	fun `get answers the feature with its worktree, workflow and steps`() {
		given(featureService.get(12, 21)).willReturn(view(FeatureWorktree(9, "/repos/12.worktrees/feat__004-features__00-docs", false)))

		mockMvc.get("$BASE/21") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.id") { value(21) }
			jsonPath("$.projectId") { value(12) }
			jsonPath("$.code") { value("004-features") }
			jsonPath("$.slug") { value("features") }
			jsonPath("$.branch") { value("feat/004-features/00-docs") }
			jsonPath("$.folder") { value("feature/004-features") }
			jsonPath("$.worktree.id") { value(9) }
			jsonPath("$.worktree.path") { value("/repos/12.worktrees/feat__004-features__00-docs") }
			jsonPath("$.worktree.missing") { value(false) }
			jsonPath("$.workflow.featureStates[0]") { value("requirements") }
			jsonPath("$.workflow.steps[0].skill") { value("feature-requirements") }
			jsonPath("$.workflow.initialState") { doesNotExist() }
			jsonPath("$.steps[0].id") { value(5) }
			jsonPath("$.steps[0].position") { value(1) }
			jsonPath("$.steps[0].type") { value("requirements") }
			jsonPath("$.steps[0].typeTitle") { value("Requirements") }
			jsonPath("$.steps[0].documentPath") { value("feature/004-features/requirements.md") }
			jsonPath("$.steps[0].status") { value("draft") }
			jsonPath("$.steps[0].states[1]") { value("approved") }
			jsonPath("$.createdAt") { value("2026-11-06T10:00:00Z") }
		}
	}

	@Test
	fun `a feature without a worktree has worktree null`() {
		given(featureService.get(12, 21)).willReturn(view(null))

		mockMvc.get("$BASE/21") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.worktree") { value(null as Any?) }
		}
	}

	@Test
	fun `not found and invalid ids`() {
		given(featureService.get(12, 22)).willThrow(FeatureNotFoundException())
		given(featureService.list(13)).willThrow(ProjectNotFoundException(13))

		mockMvc.get("$BASE/22") { auth() }.andExpect { problem(404, "feature_not_found", "Feature not found.") }
		mockMvc.get("/api/projects/13/features") { auth() }.andExpect { problem(404, "project_not_found", "Project not found.") }
		mockMvc.get("$BASE/abc") { auth() }.andExpect { problem(400, "invalid_request") }
	}

	@Test
	fun `features can't be renamed, archived or deleted`() {
		mockMvc.delete("$BASE/21") { auth() }.andExpect { status { isMethodNotAllowed() } }
		mockMvc.put("$BASE/21") { auth() }.andExpect { status { isMethodNotAllowed() } }
		mockMvc.patch("$BASE/21") { auth() }.andExpect { status { isMethodNotAllowed() } }
	}

	@Test
	fun `create answers 201 with the feature`() {
		given(featureStarter.start(12, "Features")).willReturn(StartedFeature(view(null), null))

		mockMvc.post(BASE).andExpect { status { isUnauthorized() } }
		mockMvc.post(BASE) { auth(); json("""{"name":"Features"}""") }.andExpect {
			status { isCreated() }
			jsonPath("$.id") { value(21) }
			jsonPath("$.code") { value("004-features") }
			jsonPath("$.status") { value("requirements") }
		}
	}

	@Test
	fun `a failed docs pull request is reported with the created feature`() {
		given(featureStarter.start(12, "Features")).willReturn(StartedFeature(view(null), "GitHub couldn't be reached. Try again."))

		mockMvc.post(BASE) { auth(); json("""{"name":"Features"}""") }.andExpect {
			status { isCreated() }
			jsonPath("$.id") { value(21) }
			jsonPath("$.docsPullError") { value("GitHub couldn't be reached. Try again.") }
		}
	}

	@Test
	fun `create refusals map to 400 and 409`() {
		given(featureStarter.start(12, "")).willThrow(InvalidFeatureNameException("Enter a name."))
		given(featureStarter.start(12, "w")).willThrow(WorkflowInvalidException("feature/workflow.json: the file is missing."))
		given(featureStarter.start(12, "a")).willThrow(FeatureProjectArchivedException())
		given(featureStarter.start(12, "c")).willThrow(CheckoutProjectArchivedException(12))
		given(featureStarter.start(12, "n")).willThrow(CheckoutNotReadyException())
		given(featureStarter.start(12, "m")).willThrow(CheckoutNotFoundException())

		mockMvc.post(BASE) { auth(); json("""{"name":""}""") }.andExpect {
			problem(400, "invalid_name", "Enter a name.")
			jsonPath("$.errors.name") { value("Enter a name.") }
		}
		mockMvc.post(BASE) { auth(); json("""{"name":"w"}""") }
			.andExpect { problem(409, "workflow_invalid", "feature/workflow.json: the file is missing.") }
		mockMvc.post(BASE) { auth(); json("""{"name":"a"}""") }.andExpect { problem(409, "project_archived") }
		mockMvc.post(BASE) { auth(); json("""{"name":"c"}""") }.andExpect { problem(409, "project_archived") }
		val notReady = "The project's repository checkout isn't ready. Check it out on the project page first."
		mockMvc.post(BASE) { auth(); json("""{"name":"n"}""") }.andExpect { problem(409, "checkout_not_ready", notReady) }
		mockMvc.post(BASE) { auth(); json("""{"name":"m"}""") }.andExpect { problem(409, "checkout_not_ready", notReady) }
		mockMvc.post(BASE) { auth(); json("{not json") }.andExpect { problem(400, "invalid_request") }
	}

	@Test
	fun `create git failures map to 502, 503, 504 and 500`() {
		given(featureStarter.start(12, "r")).willThrow(GitRemoteException(GitFailure.UNREACHABLE))
		given(featureStarter.start(12, "n")).willThrow(GitNotConfiguredException())
		given(featureStarter.start(12, "t")).willThrow(GitTimeoutException(Duration.ofMinutes(2)))
		given(featureStarter.start(12, "e")).willThrow(GitCommandException("git commit", 1, "secret stderr"))

		mockMvc.post(BASE) { auth(); json("""{"name":"r"}""") }.andExpect { problem(502, "git_remote_error") }
		mockMvc.post(BASE) { auth(); json("""{"name":"n"}""") }.andExpect { problem(503, "git_not_configured") }
		mockMvc.post(BASE) { auth(); json("""{"name":"t"}""") }
			.andExpect { problem(504, "git_timeout", "GitHub didn't answer within 2 minutes. Try again.") }
		mockMvc.post(BASE) { auth(); json("""{"name":"e"}""") }.andExpect {
			problem(500, "git_error", "A git command failed on the server.")
			content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret stderr"))) }
		}
	}

	@Test
	fun `progress routes need a token`() {
		mockMvc.put("$BASE/21/status") { json("""{"status":"breakdown"}""") }.andExpect { status { isUnauthorized() } }
		mockMvc.post("$BASE/21/steps") { json("""{"type":"spec","title":"x"}""") }.andExpect { status { isUnauthorized() } }
		mockMvc.patch("$BASE/21/steps/5") { json("""{"status":"approved"}""") }.andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `status answers the feature, steps answer the step`() {
		given(featureProgress.setStatus(12, 21, "breakdown")).willReturn(view(null))
		given(featureProgress.addStep(12, 21, "requirements", "Requirements", null)).willReturn(stepResult())
		given(featureProgress.updateStep(12, 21, 5, StepChanges(status = "draft"))).willReturn(stepResult())

		mockMvc.put("$BASE/21/status") { auth(); json("""{"status":"breakdown"}""") }.andExpect {
			status { isOk() }
			jsonPath("$.id") { value(21) }
		}
		mockMvc.post("$BASE/21/steps") { auth(); json("""{"type":"requirements","title":"Requirements"}""") }.andExpect {
			status { isCreated() }
			jsonPath("$.id") { value(5) }
			jsonPath("$.typeTitle") { value("Requirements") }
			jsonPath("$.states[0]") { value("draft") }
		}
		mockMvc.patch("$BASE/21/steps/5") { auth(); json("""{"status":"draft"}""") }.andExpect {
			status { isOk() }
			jsonPath("$.status") { value("draft") }
		}
	}

	@Test
	fun `progress refusals map to 409, 400 and 404`() {
		given(featureProgress.setStatus(12, 21, "shipped")).willThrow(InvalidStatusException("shipped", listOf("requirements", "done")))
		given(featureProgress.addStep(12, 21, "plan", "x", null)).willThrow(InvalidStepTypeException("plan", listOf("requirements")))
		given(featureProgress.addStep(12, 21, "spec", "", null)).willThrow(InvalidStepException("Give the step a title of up to 200 characters."))
		given(featureProgress.updateStep(12, 21, 6, StepChanges(status = "x"))).willThrow(StepNotFoundException())
		given(featureProgress.setStatus(12, 22, "done")).willThrow(FeatureNotFoundException())

		mockMvc.put("$BASE/21/status") { auth(); json("""{"status":"shipped"}""") }.andExpect {
			problem(409, "invalid_status", "\"shipped\" isn't one of this workflow's states: requirements, done.")
			jsonPath("$.allowed[1]") { value("done") }
		}
		mockMvc.post("$BASE/21/steps") { auth(); json("""{"type":"plan","title":"x"}""") }.andExpect {
			problem(409, "invalid_step_type")
			jsonPath("$.allowed[0]") { value("requirements") }
		}
		mockMvc.post("$BASE/21/steps") { auth(); json("""{"type":"spec","title":""}""") }.andExpect { problem(400, "invalid_step") }
		mockMvc.patch("$BASE/21/steps/6") { auth(); json("""{"status":"x"}""") }.andExpect { problem(404, "step_not_found") }
		mockMvc.put("$BASE/22/status") { auth(); json("""{"status":"done"}""") }.andExpect { problem(404, "feature_not_found") }
		mockMvc.patch("$BASE/21/steps/abc") { auth(); json("""{"status":"x"}""") }.andExpect { problem(400, "invalid_request") }
	}

	private fun stepResult() = view(null).let { it.steps.single() to it.workflow }

	private fun MockHttpServletRequestDsl.json(body: String) {
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun MockMvcResultMatchersDsl.problem(status: Int, code: String, detail: String? = null) {
		status { isEqualTo(status) }
		jsonPath("$.code") { value(code) }
		if (detail != null) jsonPath("$.detail") { value(detail) }
	}

	private fun MockHttpServletRequestDsl.auth() {
		header("Authorization", "Bearer test-token")
	}

	private fun feature() = Feature().apply {
		id = 21
		projectId = 12
		number = 4
		name = "Features"
		slug = "features"
		status = "requirements"
		workflow = "{}"
		branch = "feat/004-features/00-docs"
		folder = "feature/004-features"
		createdAt = AT
		updatedAt = AT
	}

	private fun view(worktree: FeatureWorktree?) = FeatureView(
		feature(),
		Workflow(
			listOf("requirements", "done"),
			listOf(WorkflowStep("requirements", "Requirements", "feature-requirements", listOf("draft", "approved"))),
		),
		listOf(FeatureStep().apply {
			id = 5
			featureId = 21
			position = 1
			typeKey = "requirements"
			title = "Requirements"
			documentPath = "feature/004-features/requirements.md"
			status = "draft"
			createdAt = AT
			updatedAt = AT
		}),
		worktree,
	)

	private companion object {
		const val BASE = "/api/projects/12/features"
		val AT: Instant = Instant.parse("2026-11-06T10:00:00Z")
	}
}
