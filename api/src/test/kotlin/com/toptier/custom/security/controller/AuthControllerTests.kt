package com.toptier.custom.security.controller

import com.toptier.custom.security.config.SecurityConfig
import com.toptier.custom.security.config.SecurityProperties
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(AuthController::class)
@Import(SecurityConfig::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class AuthControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@Test
	fun `rejects verify without a token`() {
		mockMvc.get("/api/auth/verify").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `rejects verify with a wrong token`() {
		mockMvc.get("/api/auth/verify") {
			header("Authorization", "Bearer nope")
		}.andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `accepts verify with a valid token`() {
		mockMvc.get("/api/auth/verify") {
			header("Authorization", "Bearer test-token")
		}.andExpect { status { isNoContent() } }
	}
}
