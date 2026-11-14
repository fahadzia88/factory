package com.toptier.custom.cluster

import com.toptier.custom.cluster.controller.ClusterController
import com.toptier.custom.cluster.controller.ClusterExceptionHandler
import com.toptier.custom.cluster.domain.Cluster
import com.toptier.custom.cluster.exception.ClusterNotFoundException
import com.toptier.custom.cluster.exception.ClusterValidationException
import com.toptier.custom.cluster.service.ClusterInput
import com.toptier.custom.cluster.service.ClusterService
import com.toptier.custom.security.config.SecurityConfig
import com.toptier.custom.security.config.SecurityProperties
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Instant

@WebMvcTest(ClusterController::class)
@Import(SecurityConfig::class, ClusterExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class ClusterControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@MockitoBean
	lateinit var clusterService: ClusterService

	@Test
	fun `rejects requests without a token and never calls the service`() {
		mockMvc.get("/api/clusters").andExpect { status { isUnauthorized() } }
		mockMvc.post("/api/clusters") { json(BODY) }.andExpect { status { isUnauthorized() } }
		mockMvc.put("/api/clusters/1") { json(BODY) }.andExpect { status { isUnauthorized() } }
		mockMvc.delete("/api/clusters/1").andExpect { status { isUnauthorized() } }

		then(clusterService).shouldHaveNoInteractions()
	}

	@Test
	fun `lists clusters`() {
		given(clusterService.list()).willReturn(listOf(cluster()))

		mockMvc.get("/api/clusters") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$[0].id") { value(7) }
			jsonPath("$[0].name") { value("Home lab") }
			jsonPath("$[0].address") { value("http://homelabs001:8088") }
			jsonPath("$[0].createdAt") { value("2026-11-04T10:15:30Z") }
		}
	}

	@Test
	fun `creates a cluster`() {
		given(clusterService.create(ClusterInput("Home lab", "http://homelabs001:8088"))).willReturn(cluster())

		mockMvc.post("/api/clusters") { auth(); json(BODY) }.andExpect {
			status { isCreated() }
			header { string("Location", "/api/clusters/7") }
			jsonPath("$.id") { value(7) }
			jsonPath("$.name") { value("Home lab") }
		}
	}

	@Test
	fun `returns field errors for invalid input`() {
		given(clusterService.create(ClusterInput(null, null)))
			.willThrow(ClusterValidationException(mapOf("name" to "Enter a name.", "address" to "Enter the controller's address.")))

		mockMvc.post("/api/clusters") { auth(); json("{}") }.andExpect {
			status { isBadRequest() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.detail") { value("The cluster couldn't be saved.") }
			jsonPath("$.errors.name") { value("Enter a name.") }
			jsonPath("$.errors.address") { value("Enter the controller's address.") }
		}
	}

	@Test
	fun `updates a cluster`() {
		given(clusterService.update(7, ClusterInput("Home lab", "http://homelabs001:8088"))).willReturn(cluster())

		mockMvc.put("/api/clusters/7") { auth(); json(BODY) }.andExpect {
			status { isOk() }
			jsonPath("$.address") { value("http://homelabs001:8088") }
		}
	}

	@Test
	fun `answers 404 for an unknown cluster`() {
		given(clusterService.update(404, ClusterInput("Home lab", "http://homelabs001:8088")))
			.willThrow(ClusterNotFoundException(404))
		given(clusterService.delete(404)).willThrow(ClusterNotFoundException(404))

		mockMvc.put("/api/clusters/404") { auth(); json(BODY) }.andExpect {
			status { isNotFound() }
			jsonPath("$.detail") { value("Cluster not found.") }
		}
		mockMvc.delete("/api/clusters/404") { auth() }.andExpect { status { isNotFound() } }
	}

	@Test
	fun `deletes a cluster`() {
		mockMvc.delete("/api/clusters/7") { auth() }.andExpect { status { isNoContent() } }

		then(clusterService).should().delete(7)
	}

	@Test
	fun `rejects malformed JSON`() {
		mockMvc.post("/api/clusters") { auth(); json("{not json") }.andExpect {
			status { isBadRequest() }
			jsonPath("$.detail") { value("The request body isn't valid JSON.") }
		}
	}

	private fun MockHttpServletRequestDsl.auth() {
		header("Authorization", "Bearer test-token")
	}

	private fun MockHttpServletRequestDsl.json(body: String) {
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun cluster() = Cluster().apply {
		id = 7
		name = "Home lab"
		nameKey = "home lab"
		address = "http://homelabs001:8088"
		createdAt = NOW
		updatedAt = NOW
	}

	companion object {
		private val NOW: Instant = Instant.parse("2026-11-04T10:15:30Z")
		private const val BODY = """{"name":"Home lab","address":"http://homelabs001:8088"}"""
	}
}
