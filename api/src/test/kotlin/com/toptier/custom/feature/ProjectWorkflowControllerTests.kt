package com.toptier.custom.feature

import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.feature.controller.FeatureExceptionHandler
import com.toptier.custom.feature.controller.ProjectWorkflowController
import com.toptier.custom.feature.exception.WorkflowInvalidException
import com.toptier.custom.feature.service.ProjectWorkflow
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(ProjectWorkflowController::class)
@Import(SecurityConfig::class, FeatureExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class ProjectWorkflowControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@MockitoBean
	lateinit var projectWorkflow: ProjectWorkflow

	@Test
	fun `answers 401 without a token`() {
		mockMvc.get(URL).andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `answers the workflow`() {
		given(projectWorkflow.get(12)).willReturn(
			Workflow(
				listOf("requirements", "done"),
				listOf(WorkflowStep("requirements", "Requirements", "feature-requirements", listOf("draft", "approved"))),
			),
		)
		mockMvc.get(URL) { header("Authorization", "Bearer test-token") }.andExpect {
			status { isOk() }
			jsonPath("$.featureStates[1]") { value("done") }
			jsonPath("$.steps[0].key") { value("requirements") }
			jsonPath("$.steps[0].title") { value("Requirements") }
			jsonPath("$.steps[0].skill") { value("feature-requirements") }
			jsonPath("$.steps[0].states[1]") { value("approved") }
		}
	}

	@Test
	fun `no ready checkout answers 409 checkout_not_ready`() {
		given(projectWorkflow.get(12)).willThrow(CheckoutNotReadyException())
		mockMvc.get(URL) { header("Authorization", "Bearer test-token") }.andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("checkout_not_ready") }
		}
	}

	@Test
	fun `an invalid workflow answers 409 workflow_invalid`() {
		given(projectWorkflow.get(12)).willThrow(WorkflowInvalidException("feature/workflow.json: the file is missing."))
		mockMvc.get(URL) { header("Authorization", "Bearer test-token") }.andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("workflow_invalid") }
		}
	}

	@Test
	fun `an unknown project answers 404`() {
		given(projectWorkflow.get(12)).willThrow(ProjectNotFoundException(12))
		mockMvc.get(URL) { header("Authorization", "Bearer test-token") }.andExpect {
			status { isNotFound() }
			jsonPath("$.code") { value("project_not_found") }
		}
	}

	companion object {
		const val URL = "/api/projects/12/workflow"
	}
}
