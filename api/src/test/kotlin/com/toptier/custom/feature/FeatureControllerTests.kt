package com.toptier.custom.feature

import com.toptier.custom.feature.controller.FeatureController
import com.toptier.custom.feature.controller.FeatureExceptionHandler
import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureStep
import com.toptier.custom.feature.exception.FeatureNotFoundException
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
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.put
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
