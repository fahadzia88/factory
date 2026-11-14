package com.toptier.custom.homelab

import com.sun.net.httpserver.HttpServer
import com.toptier.custom.homelab.config.HomelabClients
import com.toptier.custom.homelab.config.HomelabProperties
import com.toptier.custom.homelab.dto.ApiInfo
import com.toptier.custom.homelab.exception.HomelabApiException
import com.toptier.custom.homelab.exception.HomelabNotConfiguredException
import com.toptier.custom.homelab.exception.HomelabUnreachableException
import com.toptier.custom.homelab.service.HomelabCall
import com.toptier.custom.homelab.service.HomelabRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.core.type.TypeReference
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomelabClientsTests {

	private val builder = RestClient.builder()
	private val server = MockRestServiceServer.bindTo(builder).build()
	private val call = HomelabCall(HomelabClients(builder, HomelabProperties(token = "test-token"), applyTimeouts = false))

	@Test
	fun `sends the bearer token and reads the JSON answer`() {
		server.expect(requestTo("$CONTROLLER/api"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
			.andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
			.andRespond(withSuccess("""{"readOnly":true,"namespaceSelector":"managed=true","extra":1}""", MediaType.APPLICATION_JSON))

		val info = call.call(CONTROLLER, HomelabRequest(HttpMethod.GET, "/api"), object : TypeReference<ApiInfo>() {})

		assertTrue(info.readOnly)
		assertEquals("managed=true", info.namespaceSelector)
		server.verify()
	}

	@Test
	fun `encodes path variables and skips null query values`() {
		server.expect(requestTo("$CONTROLLER/api/pods/my%20ns/web-1/logs?tail=50&previous=true"))
			.andRespond(withSuccess("line", MediaType.TEXT_PLAIN))

		val text = call.text(
			CONTROLLER,
			HomelabRequest(
				HttpMethod.GET,
				"/api/pods/{namespace}/{name}/logs",
				vars = mapOf("namespace" to "my ns", "name" to "web-1"),
				query = linkedMapOf("tail" to 50, "since" to null, "previous" to true),
			),
		)

		assertEquals("line", text)
		server.verify()
	}

	@Test
	fun `sends a JSON body and a raw text body`() {
		server.expect(requestTo("$CONTROLLER/api/workloads/apps/deployments/web/scale"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().json("""{"replicas":3}"""))
			.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
		server.expect(requestTo("$CONTROLLER/api/apply?dryRun=true"))
			.andExpect(content().string("kind: ConfigMap"))
			.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

		call.text(
			CONTROLLER,
			HomelabRequest(
				HttpMethod.POST, "/api/workloads/{ns}/{kind}/{name}/scale",
				vars = mapOf("ns" to "apps", "kind" to "deployments", "name" to "web"), json = mapOf("replicas" to 3),
			),
		)
		call.text(CONTROLLER, HomelabRequest(HttpMethod.POST, "/api/apply", query = mapOf("dryRun" to true), text = "kind: ConfigMap"))

		server.verify()
	}

	@Test
	fun `turns an error answer into an exception with the controller's message`() {
		server.expect(requestTo("$CONTROLLER/api/pods/apps/x"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND).body("""{"error":"pods \"x\" not found"}""").contentType(MediaType.APPLICATION_JSON))

		val ex = assertThrows<HomelabApiException> {
			call.text(CONTROLLER, HomelabRequest(HttpMethod.GET, "/api/pods/apps/x"))
		}

		assertEquals(404, ex.status)
		assertEquals("pods \"x\" not found", ex.message)
		assertNull(ex.results)
	}

	@Test
	fun `makes no request without a token`() {
		val noToken = HomelabCall(HomelabClients(builder, HomelabProperties(token = ""), applyTimeouts = false))

		assertThrows<HomelabNotConfiguredException> { noToken.text(CONTROLLER, HomelabRequest(HttpMethod.GET, "/api")) }

		server.verify()
	}

	@Test
	fun `gives up on a controller slower than the timeout`() {
		val slow = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		slow.createContext("/") { exchange ->
			Thread.sleep(1500)
			exchange.sendResponseHeaders(200, -1)
			exchange.close()
		}
		slow.start()
		try {
			val clients = HomelabClients(RestClient.builder(), HomelabProperties(token = "t", timeout = Duration.ofMillis(200)))

			assertThrows<HomelabUnreachableException> {
				HomelabCall(clients).text("http://127.0.0.1:${slow.address.port}", HomelabRequest(HttpMethod.GET, "/api"))
			}
		} finally {
			slow.stop(0)
		}
	}

	@Test
	fun `reports a refused connection as unreachable`() {
		val closedPort = ServerSocket(0).use { it.localPort }
		val clients = HomelabClients(RestClient.builder(), HomelabProperties(token = "t", timeout = Duration.ofSeconds(2)))

		val ex = assertThrows<HomelabUnreachableException> {
			HomelabCall(clients).text("http://127.0.0.1:$closedPort", HomelabRequest(HttpMethod.GET, "/api"))
		}

		assertTrue(ex.message!!.startsWith("Couldn't reach the controller"))
	}

	companion object {
		private const val CONTROLLER = "http://homelabs001:8088"
	}
}
