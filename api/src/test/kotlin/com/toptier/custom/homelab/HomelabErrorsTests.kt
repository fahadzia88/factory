package com.toptier.custom.homelab

import com.toptier.custom.homelab.exception.HomelabApiException
import com.toptier.custom.homelab.exception.HomelabNotConfiguredException
import com.toptier.custom.homelab.exception.HomelabUnreachableException
import com.toptier.custom.homelab.service.HomelabErrors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomelabErrorsTests {

	@Test
	fun `keeps the controller's message unchanged for every error status`() {
		val messages = mapOf(
			400 to "Invalid kind \"pods\". Use deployments or statefulsets.",
			401 to "Unauthorized",
			403 to "Namespace \"kube-system\" isn't managed by this controller.",
			404 to "deployments \"web\" not found",
			409 to "Pod redis-0 has no owner, so deleting it would not bring it back. Use ?force=true.",
			413 to "Body is larger than 5 MB.",
			422 to "Apply failed on Deployment/web.",
			502 to "Kubernetes API unreachable: connect ECONNREFUSED",
			503 to "metrics-server isn't installed in this cluster.",
		)

		for ((status, message) in messages) {
			val body = """{"error":${quote(message)}}"""
			val ex = HomelabErrors.fromResponse(status, body)

			assertEquals(status, ex.status, "status $status")
			assertEquals(message, ex.message, "message of $status")
		}
	}

	@Test
	fun `keeps the per-object results and dry run flag of a failed apply`() {
		val body = """
			{"error":"Apply failed on Service/web.","dryRun":false,"results":[
			 {"kind":"Deployment","namespace":"apps","name":"web","status":"applied","resourceVersion":"123"},
			 {"kind":"Service","namespace":"apps","name":"web","status":"failed","error":"port is invalid"}]}
		""".trimIndent()

		val ex = HomelabErrors.fromResponse(422, body)

		assertEquals("Apply failed on Service/web.", ex.message)
		assertEquals(false, ex.dryRun)
		assertEquals(listOf("applied", "failed"), ex.results?.map { it.status })
		assertEquals("port is invalid", ex.results?.get(1)?.error)
		assertEquals("123", ex.results?.get(0)?.resourceVersion)
	}

	@Test
	fun `falls back to the response text when the body isn't the error shape`() {
		assertEquals("Bad Gateway from proxy", HomelabErrors.fromResponse(502, "Bad Gateway from proxy").message)
		assertEquals("The controller answered with status 500.", HomelabErrors.fromResponse(500, "").message)
		assertEquals("The controller answered with status 500.", HomelabErrors.fromResponse(500, null).message)
		assertEquals("The controller answered with status 400.", HomelabErrors.fromResponse(400, """{"other":1}""").message)
		assertNull(HomelabErrors.fromResponse(400, """{"error":"x"}""").results)
	}

	@Test
	fun `maps a response exception to an api exception`() {
		val error = RestClientResponseException(
			"404 Not Found", 404, "Not Found", null, """{"error":"pods \"x\" not found"}""".toByteArray(), Charsets.UTF_8,
		)

		val mapped = HomelabErrors.map(error)

		val api = assertIs<HomelabApiException>(mapped)
		assertEquals(404, api.status)
		assertEquals("pods \"x\" not found", api.message)
	}

	@Test
	fun `maps timeouts and refused connections to unreachable`() {
		val timeout = HomelabErrors.map(ResourceAccessException("I/O error", SocketTimeoutException("Read timed out")))
		val refused = HomelabErrors.map(ResourceAccessException("I/O error", ConnectException("Connection refused")))

		assertIs<HomelabUnreachableException>(timeout)
		assertTrue(timeout.message!!.contains("Read timed out"))
		assertIs<HomelabUnreachableException>(refused)
		assertTrue(refused.message!!.contains("Connection refused"))
		assertNull(refused.status)
	}

	@Test
	fun `keeps its own exceptions and rethrows unknown ones`() {
		val own = HomelabNotConfiguredException()
		assertEquals(own, HomelabErrors.map(own))

		assertThrows<IllegalStateException> { HomelabErrors.map(IllegalStateException("bug")) }
		assertTrue(HomelabErrors.map(ResourceAccessException("x", IOException())) is HomelabUnreachableException)
	}

	private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
