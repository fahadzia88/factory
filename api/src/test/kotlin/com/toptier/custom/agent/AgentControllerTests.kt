package com.toptier.custom.agent

import com.toptier.custom.agent.controller.AgentController
import com.toptier.custom.agent.controller.AgentExceptionHandler
import com.toptier.custom.agent.exception.AgentSessionNotFoundException
import com.toptier.custom.agent.exception.AgentTimeoutException
import com.toptier.custom.agent.exception.AgentTurnFailedException
import com.toptier.custom.agent.service.AgentReply
import com.toptier.custom.agent.service.AgentService
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(AgentController::class)
@Import(SecurityConfig::class, AgentExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class AgentControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@MockitoBean
	lateinit var agentService: AgentService

	@Test
	fun `rejects requests without a token`() {
		ask("""{"message":"hi"}""", token = null).andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `starts a conversation`() {
		given(agentService.ask("hi", null)).willReturn(AgentReply("wrun_1", "Hello!"))

		ask("""{"message":"hi"}""").andExpect {
			status { isOk() }
			jsonPath("$.sessionId") { value("wrun_1") }
			jsonPath("$.reply") { value("Hello!") }
		}
	}

	@Test
	fun `continues a conversation`() {
		given(agentService.ask("and?", "wrun_1")).willReturn(AgentReply("wrun_1", "More."))

		ask("""{"message":"and?","sessionId":"wrun_1"}""").andExpect {
			status { isOk() }
			jsonPath("$.reply") { value("More.") }
		}
	}

	@Test
	fun `requires a message`() {
		ask("""{"message":"  "}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.detail") { value("message is required") }
		}
	}

	@Test
	fun `maps agent failures to problem details`() {
		given(agentService.ask("slow", null)).willThrow(AgentTimeoutException("The agent did not answer within 120s"))
		given(agentService.ask("broken", null)).willThrow(AgentTurnFailedException("The agent's turn failed: boom", "wrun_1"))
		given(agentService.ask("hi", "wrun_gone")).willThrow(AgentSessionNotFoundException("wrun_gone"))

		ask("""{"message":"slow"}""").andExpect {
			status { isGatewayTimeout() }
			jsonPath("$.detail") { value("The agent did not answer within 120s") }
		}
		ask("""{"message":"broken"}""").andExpect {
			status { isBadGateway() }
			jsonPath("$.sessionId") { value("wrun_1") }
		}
		ask("""{"message":"hi","sessionId":"wrun_gone"}""").andExpect { status { isNotFound() } }
	}

	private fun ask(body: String, token: String? = "test-token") =
		mockMvc.post("/api/agent/ask") {
			token?.let { header("Authorization", "Bearer $it") }
			contentType = MediaType.APPLICATION_JSON
			content = body
		}
}
