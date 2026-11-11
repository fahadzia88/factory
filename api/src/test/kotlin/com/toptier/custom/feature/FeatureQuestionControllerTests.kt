package com.toptier.custom.feature

import com.toptier.custom.feature.controller.FeatureExceptionHandler
import com.toptier.custom.feature.controller.FeatureQuestionController
import com.toptier.custom.feature.domain.FeatureQuestion
import com.toptier.custom.feature.exception.FeatureNotFoundException
import com.toptier.custom.feature.exception.InvalidQuestionsException
import com.toptier.custom.feature.service.FeatureQuestions
import com.toptier.custom.security.config.SecurityConfig
import com.toptier.custom.security.config.SecurityProperties
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(FeatureQuestionController::class)
@Import(SecurityConfig::class, FeatureExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class FeatureQuestionControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@MockitoBean
	lateinit var featureQuestions: FeatureQuestions

	@Test
	fun `creates a set, or explains why not`() {
		given(featureQuestions.create(eq(12L), eq(21L), any())).willReturn(FeatureQuestion().apply { id = 7 })
		given(featureQuestions.create(eq(12L), eq(22L), any())).willThrow(InvalidQuestionsException("Question 1: give 2 to 4 options."))
		given(featureQuestions.create(eq(12L), eq(23L), any())).willThrow(FeatureNotFoundException())

		mockMvc.post(BASE) { contentType = MediaType.APPLICATION_JSON; content = "{}" }.andExpect { status { isUnauthorized() } }
		mockMvc.post(BASE) { auth(); contentType = MediaType.APPLICATION_JSON; content = BODY }.andExpect {
			status { isCreated() }
			jsonPath("$.id") { value(7) }
		}
		mockMvc.post("/api/projects/12/features/22/questions") { auth(); contentType = MediaType.APPLICATION_JSON; content = BODY }.andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("invalid_questions") }
			jsonPath("$.detail") { value("Question 1: give 2 to 4 options.") }
		}
		mockMvc.post("/api/projects/12/features/23/questions") { auth(); contentType = MediaType.APPLICATION_JSON; content = BODY }.andExpect {
			status { isNotFound() }
			jsonPath("$.code") { value("feature_not_found") }
		}
	}

	private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.auth() {
		header("Authorization", "Bearer test-token")
	}

	private companion object {
		const val BASE = "/api/projects/12/features/21/questions"
		const val BODY = """{"questions":[{"header":"Token","question":"Which?","multiSelect":false,"options":[{"label":"A","description":"a"},{"label":"B","description":"b"}]}]}"""
	}
}
