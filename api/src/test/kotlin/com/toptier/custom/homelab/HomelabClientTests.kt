package com.toptier.custom.homelab

import com.toptier.custom.homelab.config.HomelabClients
import com.toptier.custom.homelab.config.HomelabProperties
import com.toptier.custom.homelab.exception.HomelabApiException
import com.toptier.custom.homelab.exception.HomelabBadRequestException
import com.toptier.custom.homelab.service.HomelabCall
import com.toptier.custom.homelab.service.HomelabClient
import com.toptier.custom.homelab.service.WorkloadKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.RequestMatcher
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomelabClientTests {

	private val builder = RestClient.builder()
	private val server = MockRestServiceServer.bindTo(builder).build()
	private val client = HomelabClient(
		HomelabCall(HomelabClients(builder, HomelabProperties(token = "test-token"), applyTimeouts = false)),
	)

	@AfterEach
	fun verify() {
		server.verify()
	}

	@Test
	fun `reads health, readiness and the controller's mode`() {
		expectGet("/healthz", """{"status":"ok"}""")
		expectGet("/readyz", """{"status":"ok","kubernetes":"v1.31.4+k3s1"}""")
		expectGet("/api", INFO)

		assertTrue(client.health(CONTROLLER))
		val ready = client.ready(CONTROLLER)
		assertTrue(ready.ok)
		assertEquals("v1.31.4+k3s1", ready.kubernetes)
		val info = client.info(CONTROLLER)
		assertFalse(info.readOnly)
		assertEquals("managed=true", info.namespaceSelector)
		assertEquals(listOf("GET /api/overview"), info.endpoints?.monitoring)
	}

	@Test
	fun `treats an unready controller as an answer`() {
		server.expect(requestTo("$CONTROLLER/readyz"))
			.andRespond(
				withStatus(HttpStatus.SERVICE_UNAVAILABLE)
					.body("""{"status":"unavailable","error":"connect ECONNREFUSED 10.0.0.1:6443"}""")
					.contentType(MediaType.APPLICATION_JSON),
			)

		val ready = client.ready(CONTROLLER)

		assertFalse(ready.ok)
		assertEquals("connect ECONNREFUSED 10.0.0.1:6443", ready.error)
	}

	@Test
	fun `sends the bearer token on every call`() {
		server.expect(ExpectedCount.manyTimes(), requestTo("$CONTROLLER/api/nodes"))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

		client.nodes(CONTROLLER)
		client.nodes(CONTROLLER)
	}

	@Test
	fun `reads the monitoring lists with their filters`() {
		expectGet("/api/overview", OVERVIEW)
		expectGet("/api/namespaces", """[{"name":"apps","phase":"Active","labels":{"managed":"true"},"createdAt":"2026-11-01T10:00:00Z"}]""")
		expectGet("/api/nodes", NODES)
		expectGet("/api/workloads?namespace=apps", """[$WORKLOAD]""")
		expectGet("/api/pods?namespace=apps&problems=true", """[$POD]""")
		expectGet("/api/events?namespace=apps&type=Warning&limit=50", """[$EVENT]""")
		expectGet("/api/metrics?namespace=apps", """{"nodes":[],"pods":[{"namespace":"apps","name":"web-1","cpuMillicores":12,"memoryMiB":64,"containers":[]}]}""")

		assertEquals(1, client.overview(CONTROLLER).workloads.total)
		assertEquals("apps", client.namespaces(CONTROLLER).single().name)
		val node = client.nodes(CONTROLLER).single()
		assertEquals(listOf("DiskPressure"), node.pressure)
		assertEquals(37.5, node.usage?.cpuPercent)
		assertEquals("web", client.workloads(CONTROLLER, "apps").single().name)
		assertEquals("ImagePullBackOff", client.pods(CONTROLLER, "apps", problemsOnly = true).single().problem?.reason)
		assertEquals("Pod/web-1", client.events(CONTROLLER, "apps", "Warning", 50).single().`object`)
		assertEquals(12.0, client.metrics(CONTROLLER, "apps").pods.single().cpuMillicores)
	}

	@Test
	fun `omits filters that aren't set`() {
		expectGet("/api/workloads", "[]")
		expectGet("/api/pods", "[]")
		expectGet("/api/events", "[]")
		expectGet("/api/metrics", """{"nodes":[],"pods":[]}""")

		client.workloads(CONTROLLER)
		client.pods(CONTROLLER)
		client.events(CONTROLLER)
		client.metrics(CONTROLLER)
	}

	@Test
	fun `maps workload kinds both ways and rejects others`() {
		assertEquals(WorkloadKind.DEPLOYMENTS, WorkloadKind.fromResponse("Deployment"))
		assertEquals(WorkloadKind.STATEFULSETS, WorkloadKind.fromResponse("StatefulSet"))
		assertEquals("deployments", WorkloadKind.fromResponse("Deployment").path)
		assertEquals("statefulsets", WorkloadKind.fromResponse("StatefulSet").path)
		assertEquals(WorkloadKind.DEPLOYMENTS, WorkloadKind.fromPath("deploy"))
		assertEquals(WorkloadKind.STATEFULSETS, WorkloadKind.fromPath("sts"))
		assertThrows<HomelabBadRequestException> { WorkloadKind.fromPath("pods") }
		assertThrows<HomelabBadRequestException> { WorkloadKind.fromResponse("DaemonSet") }
	}

	@Test
	fun `reads a workload with its pods and events using the mapped kind`() {
		expectGet(
			"/api/workloads/apps/statefulsets/redis",
			WORKLOAD.replace("\"Deployment\"", "\"StatefulSet\"").dropLast(1) + ""","pods":[$POD],"events":[$EVENT]}""",
		)

		val detail = client.workload(CONTROLLER, "apps", WorkloadKind.fromResponse("StatefulSet"), "redis")

		assertEquals("StatefulSet", detail.workload.kind)
		assertEquals("web-1", detail.pods.single().name)
		assertEquals("BackOff", detail.events.single().reason)
	}

	@Test
	fun `reads a pod with its events`() {
		expectGet("/api/pods/apps/web-1", POD.dropLast(1) + ""","events":[$EVENT]}""")

		val detail = client.pod(CONTROLLER, "apps", "web-1")

		assertEquals("ImagePullBackOff", detail.pod.problem?.reason)
		assertEquals("Back-off pulling image", detail.pod.problem?.message)
		assertEquals(1, detail.events.size)
	}

	@Test
	fun `restarts a workload with no body`() {
		server.expect(requestTo("$CONTROLLER/api/workloads/apps/deployments/web/restart"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().string(""))
			.andRespond(withSuccess(WORKLOAD, MediaType.APPLICATION_JSON))

		assertEquals("progressing", client.restart(CONTROLLER, "apps", WorkloadKind.DEPLOYMENTS, "web").status)
	}

	@Test
	fun `scales with the replica count as JSON`() {
		server.expect(requestTo("$CONTROLLER/api/workloads/apps/deployments/web/scale"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().json("""{"replicas":0}"""))
			.andRespond(withSuccess(WORKLOAD, MediaType.APPLICATION_JSON))

		client.scale(CONTROLLER, "apps", WorkloadKind.DEPLOYMENTS, "web", 0)
	}

	@Test
	fun `changes an image and sends the container only when given`() {
		server.expect(requestTo("$CONTROLLER/api/workloads/apps/deployments/web/image"))
			.andExpect(content().json("""{"image":"nginx:1.27"}""", true))
			.andRespond(withSuccess(WORKLOAD, MediaType.APPLICATION_JSON))
		server.expect(requestTo("$CONTROLLER/api/workloads/apps/statefulsets/db/image"))
			.andExpect(content().json("""{"image":"redis:7","container":"cache"}""", true))
			.andRespond(withSuccess(WORKLOAD, MediaType.APPLICATION_JSON))

		client.setImage(CONTROLLER, "apps", WorkloadKind.DEPLOYMENTS, "web", "nginx:1.27")
		client.setImage(CONTROLLER, "apps", WorkloadKind.STATEFULSETS, "db", "redis:7", "cache")
	}

	@Test
	fun `restarts a pod, forcing only when asked`() {
		server.expect(requestTo("$CONTROLLER/api/pods/apps/web-1/restart"))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withStatus(HttpStatus.ACCEPTED).body(POD_RESTART).contentType(MediaType.APPLICATION_JSON))
		server.expect(requestTo("$CONTROLLER/api/pods/apps/orphan/restart?force=true"))
			.andRespond(withStatus(HttpStatus.ACCEPTED).body(POD_RESTART).contentType(MediaType.APPLICATION_JSON))

		val restart = client.restartPod(CONTROLLER, "apps", "web-1")
		client.restartPod(CONTROLLER, "apps", "orphan", force = true)

		assertTrue(restart.deleted)
		assertEquals("ReplicaSet/web-5d8", restart.owner)
	}

	@Test
	fun `passes a pod restart conflict through`() {
		server.expect(requestTo("$CONTROLLER/api/pods/apps/orphan/restart"))
			.andRespond(
				withStatus(HttpStatus.CONFLICT)
					.body("""{"error":"Pod orphan has no owner and won't come back. Retry with ?force=true."}""")
					.contentType(MediaType.APPLICATION_JSON),
			)

		val ex = assertThrows<HomelabApiException> { client.restartPod(CONTROLLER, "apps", "orphan") }

		assertEquals(409, ex.status)
		assertEquals("Pod orphan has no owner and won't come back. Retry with ?force=true.", ex.message)
	}

	@Test
	fun `applies raw text, with the namespace and dry run only when set`() {
		server.expect(requestTo("$CONTROLLER/api/apply?namespace=apps&dryRun=true"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().string(YAML))
			.andExpect { request -> assertTrue(request.headers.contentType.toString().startsWith("text/plain")) }
			.andRespond(withSuccess(APPLY_VALID, MediaType.APPLICATION_JSON))
		server.expect(requestTo("$CONTROLLER/api/apply"))
			.andExpect(content().string(YAML))
			.andRespond(withSuccess(APPLY_VALID.replace("true", "false").replace("valid", "applied"), MediaType.APPLICATION_JSON))

		val dry = client.apply(CONTROLLER, YAML, namespace = "apps", dryRun = true)
		val real = client.apply(CONTROLLER, YAML)

		assertTrue(dry.dryRun)
		assertEquals("valid", dry.results.single().status)
		assertFalse(real.dryRun)
		assertEquals("applied", real.results.single().status)
	}

	@Test
	fun `keeps the per-object results of a failed apply`() {
		server.expect(requestTo("$CONTROLLER/api/apply"))
			.andRespond(
				withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
					.body(
						"""{"error":"Apply failed on Service/web.","dryRun":false,"results":[
						|{"kind":"ConfigMap","namespace":"apps","name":"cfg","status":"applied","resourceVersion":"9"},
						|{"kind":"Service","namespace":"apps","name":"web","status":"failed","error":"port is invalid"}]}"""
							.trimMargin(),
					)
					.contentType(MediaType.APPLICATION_JSON),
			)

		val ex = assertThrows<HomelabApiException> { client.apply(CONTROLLER, YAML) }

		assertEquals(422, ex.status)
		assertEquals("Apply failed on Service/web.", ex.message)
		assertEquals(listOf("applied", "failed"), ex.results?.map { it.status })
		assertEquals("port is invalid", ex.results?.last()?.error)
	}

	@Test
	fun `keeps the message of a body over the limit`() {
		server.expect(requestTo("$CONTROLLER/api/apply"))
			.andRespond(withStatus(HttpStatus.valueOf(413)).body("""{"error":"Request body is larger than 5 MB."}""").contentType(MediaType.APPLICATION_JSON))

		val ex = assertThrows<HomelabApiException> { client.apply(CONTROLLER, YAML) }

		assertEquals(413, ex.status)
		assertEquals("Request body is larger than 5 MB.", ex.message)
		assertNull(ex.results)
	}

	@Test
	fun `never puts the token in a URL`() {
		val noToken = RequestMatcher { request ->
			assertFalse(request.uri.toString().contains("access_token"))
			assertFalse(request.uri.toString().contains("test-token"))
		}
		server.expect(ExpectedCount.manyTimes(), noToken).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

		client.workloads(CONTROLLER, "apps")
		client.pods(CONTROLLER, "apps", problemsOnly = true)
		client.events(CONTROLLER, "apps", "Warning", 10)
	}

	private fun expectGet(path: String, body: String) {
		server.expect(requestTo("$CONTROLLER$path"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
	}

	companion object {
		private const val CONTROLLER = "http://homelabs001:8088"
		private const val YAML = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cfg\n"

		private const val INFO =
			"""{"readOnly":false,"namespaceSelector":"managed=true","endpoints":{"monitoring":["GET /api/overview"],"control":["POST /api/apply"]}}"""

		private const val WORKLOAD =
			"""{"namespace":"apps","kind":"Deployment","name":"web","status":"progressing",
				"replicas":{"desired":2,"current":2,"ready":1,"updated":1,"available":1},
				"images":[{"container":"web","image":"nginx:1.27"}],"restartedAt":"2026-11-14T10:00:00Z","createdAt":"2026-11-01T10:00:00Z"}"""

		private const val POD =
			"""{"namespace":"apps","name":"web-1","phase":"Pending","ready":"0/1","restarts":0,"node":"homelabs001","ip":"10.42.0.7",
				"owner":{"kind":"Deployment","name":"web"},"createdAt":"2026-11-14T10:00:00Z","deleting":false,
				"problem":{"container":"web","reason":"ImagePullBackOff","message":"Back-off pulling image"},
				"containers":[{"name":"web","image":"nginx:nope","ready":false,"restarts":0,"state":"waiting","reason":"ImagePullBackOff"}]}"""

		private const val EVENT =
			"""{"namespace":"apps","type":"Warning","reason":"BackOff","message":"Back-off pulling image","object":"Pod/web-1","count":3,"lastSeen":"2026-11-14T10:01:00Z"}"""

		private const val NODES =
			"""[{"name":"homelabs001","ready":true,"schedulable":true,"roles":["control-plane"],"addresses":["InternalIP=192.168.1.10"],
				"kubelet":"v1.31.4+k3s1","os":"linux","arch":"amd64","runtime":"containerd://1.7","allocatable":{"cpuMillicores":4000,"memoryMiB":8000,"pods":110},
				"pressure":["DiskPressure"],"createdAt":"2026-10-01T10:00:00Z","usage":{"cpuMillicores":1500,"memoryMiB":4000,"cpuPercent":37.5,"memoryPercent":50}}]"""

		private const val OVERVIEW =
			"""{"kubernetes":"v1.31.4+k3s1","namespaces":["apps"],
				"nodes":{"total":1,"ready":1,"items":[{"name":"homelabs001","ready":true,"roles":["control-plane"],"pressure":[]}]},
				"workloads":{"total":1,"ready":0,"notReady":[{"namespace":"apps","kind":"Deployment","name":"web","status":"progressing","replicas":{"desired":2,"current":2,"ready":1,"updated":1,"available":1}}]},
				"pods":{"total":1,"byPhase":{"Pending":1},"problems":[{"namespace":"apps","name":"web-1","restarts":0,"reason":"ImagePullBackOff"}]},
				"warningsLastHour":3}"""

		private const val POD_RESTART =
			"""{"namespace":"apps","name":"web-1","deleted":true,"owner":"ReplicaSet/web-5d8","message":"Pod deleted; it will be recreated."}"""

		private const val APPLY_VALID =
			"""{"dryRun":true,"results":[{"kind":"ConfigMap","namespace":"apps","name":"cfg","status":"valid"}]}"""
	}
}
