package com.toptier.custom.homelab

import com.toptier.custom.homelab.controller.HomelabController
import com.toptier.custom.homelab.controller.HomelabExceptionHandler
import com.toptier.custom.homelab.dto.ApplyObjectResult
import com.toptier.custom.homelab.exception.HomelabApiException
import com.toptier.custom.homelab.exception.HomelabNotConfiguredException
import com.toptier.custom.homelab.exception.HomelabUnreachableException
import com.toptier.custom.security.config.SecurityConfig
import com.toptier.custom.security.config.SecurityProperties
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Throws what the case name says, so the advice can be checked on its own. */
@RestController
@RequestMapping("/api/homelab-test")
class FakeHomelabController : HomelabController {

	@GetMapping("/{case}")
	fun fail(@PathVariable case: String): String = throw when (case) {
		"unconfigured" -> HomelabNotConfiguredException()
		"unreachable" -> HomelabUnreachableException("Read timed out")
		"apply" -> HomelabApiException(
			422, "Apply failed on Service/web.", dryRun = false,
			results = listOf(
				ApplyObjectResult(kind = "Deployment", name = "web", status = "applied"),
				ApplyObjectResult(kind = "Service", name = "web", status = "failed", error = "port is invalid"),
			),
		)
		else -> HomelabApiException(case.toInt(), "controller says $case")
	}
}

@WebMvcTest(FakeHomelabController::class)
@Import(SecurityConfig::class, HomelabExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class HomelabExceptionHandlerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@Test
	fun `passes controller errors through with the controller's message and status`() {
		for (status in listOf(400, 403, 404, 409, 413, 503)) {
			mockMvc.get("/api/homelab-test/$status") { auth() }.andExpect {
				status { isEqualTo(status) }
				content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
				jsonPath("$.detail") { value("controller says $status") }
				jsonPath("$.controllerStatus") { value(status) }
			}
		}
	}

	@Test
	fun `answers a controller 401 as 502 so the app doesn't sign the user out`() {
		mockMvc.get("/api/homelab-test/401") { auth() }.andExpect {
			status { isBadGateway() }
			jsonPath("$.detail") { value("controller says 401") }
			jsonPath("$.controllerStatus") { value(401) }
		}
	}

	@Test
	fun `says the cluster is unreachable for a controller 502 and for no answer`() {
		mockMvc.get("/api/homelab-test/502") { auth() }.andExpect {
			status { isBadGateway() }
			jsonPath("$.detail") { value("Cluster unreachable: controller says 502") }
			jsonPath("$.controllerStatus") { value(502) }
		}
		mockMvc.get("/api/homelab-test/unreachable") { auth() }.andExpect {
			status { isBadGateway() }
			jsonPath("$.detail") { value("Cluster unreachable: Read timed out") }
			jsonPath("$.controllerStatus") { doesNotExist() }
		}
	}

	@Test
	fun `answers other controller statuses as 502`() {
		mockMvc.get("/api/homelab-test/500") { auth() }.andExpect {
			status { isBadGateway() }
			jsonPath("$.detail") { value("controller says 500") }
			jsonPath("$.controllerStatus") { value(500) }
		}
	}

	@Test
	fun `keeps the results of a failed apply`() {
		mockMvc.get("/api/homelab-test/apply") { auth() }.andExpect {
			status { isUnprocessableContent() }
			jsonPath("$.detail") { value("Apply failed on Service/web.") }
			jsonPath("$.dryRun") { value(false) }
			jsonPath("$.results[0].status") { value("applied") }
			jsonPath("$.results[1].status") { value("failed") }
			jsonPath("$.results[1].error") { value("port is invalid") }
		}
	}

	@Test
	fun `answers 503 when the token isn't configured, without a controller status`() {
		mockMvc.get("/api/homelab-test/unconfigured") { auth() }.andExpect {
			status { isServiceUnavailable() }
			jsonPath("$.detail") { value("The controller token isn't configured on the server. Set CONTROLLER_API_TOKEN and restart the api.") }
			jsonPath("$.controllerStatus") { doesNotExist() }
		}
	}

	private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.auth() {
		header("Authorization", "Bearer test-token")
	}
}
