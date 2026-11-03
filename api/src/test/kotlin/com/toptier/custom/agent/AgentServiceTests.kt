package com.toptier.custom.agent

import com.toptier.custom.agent.config.AgentProperties
import com.toptier.custom.agent.exception.AgentSessionNotFoundException
import com.toptier.custom.agent.exception.AgentTimeoutException
import com.toptier.custom.agent.exception.AgentTurnFailedException
import com.toptier.custom.agent.exception.AgentUnavailableException
import com.toptier.custom.agent.service.AgentReply
import com.toptier.custom.agent.service.AgentService
import com.toptier.custom.support.MutableClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentServiceTests {

	private val builder = RestClient.builder().baseUrl(AGENT)
	private val server = MockRestServiceServer.bindTo(builder).build()
	private val clock = MutableClock()
	private val sleeps = mutableListOf<Duration>()
	private val service = service(clock)

	private fun service(clock: Clock) =
		AgentService(builder.build(), JsonMapper.builder().build(), AgentProperties(AGENT, Duration.ofMinutes(2)), clock)
			.also { it.sleeper = { pause -> sleeps += pause; (clock as? MutableClock)?.advance(pause) } }

	@AfterEach
	fun verify() {
		server.verify()
	}

	@Test
	fun `starts a session and returns the reply parts of its turn`() {
		expectCreate()
		expectStream(
			0,
			line("""{"type":"session.started","data":{}},{"type":"delivery.admitted","data":{"deliveryId":"d_1"}}"""),
			"""{"progress":{"type":"content.delta","scope":{"turnId":"turn_0"},"data":{"partId":"part_0","delta":"Let me"}}}""",
			HEARTBEAT,
			line(
				"""{"type":"content.completed","scope":{"turnId":"turn_0","runId":"run_0"},
				  "data":{"partId":"part_0","kind":"text","phase":"narration","value":"Let me check."}},
				 {"type":"content.completed","scope":{"turnId":"turn_0"},"data":{"partId":"part_1","kind":"reasoning","phase":"reply","value":"hmm"}},
				 {"type":"content.completed","scope":{"turnId":"turn_0"},"data":{"partId":"part_2","kind":"text","phase":"reply","value":"Hello"}},
				 {"type":"content.completed","scope":{"turnId":"turn_0"},"data":{"partId":"part_3","kind":"text","phase":"reply","value":"there!","future":1}},
				 {"type":"something.new","data":{"x":1}}""",
			),
			line(
				"""{"type":"turn.settled","scope":{"turnId":"turn_0"},"data":{"turnId":"turn_0","outcome":"completed","reply":["part_2","part_3"]}},
				 {"type":"delivery.settled","data":{"deliveryId":"d_1","outcome":"handled","turnId":"turn_0"}}""",
			),
		)

		val reply = service.ask("hello")

		assertEquals("wrun_1", reply.sessionId)
		assertEquals("Hello\n\nthere!", reply.text)
	}

	@Test
	fun `reconnects after a lease ends from the stored lines read`() {
		expectCreate()
		// Two stored lines, then transport records that are not counted
		expectStream(0, line("""{"type":"delivery.admitted","data":{"deliveryId":"d_1"}}"""), PROGRESS, HEARTBEAT, LEASE_ENDED)
		expectStream(2, *completedTurn("turn_0", "d_1", "Hi").toTypedArray())

		assertEquals("Hi", service.ask("hello").text)
	}

	@Test
	fun `reconnects when the connection closes without a transport record`() {
		expectCreate()
		expectStream(0, PROGRESS)
		expectStream(1, *completedTurn("turn_0", "d_1", "Hi").toTypedArray())

		assertEquals("Hi", service.ask("hello").text)
	}

	@Test
	fun `continues a session, retrying while it is not ready, and ignores earlier turns`() {
		server.expect(requestTo("$AGENT/eve/v1/session/wrun_1"))
			.andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body(NOT_READY))
		server.expect(requestTo("$AGENT/eve/v1/session/wrun_1"))
			.andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body(NOT_READY))
		server.expect(requestTo("$AGENT/eve/v1/session/wrun_1"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(jsonPath("$.message").value("and now?"))
			.andRespond(withSuccess("""{"ok":true,"deliveryId":"d_2"}""", MediaType.APPLICATION_JSON))
		expectStream(
			0,
			*completedTurn("turn_0", "d_1", "First answer").toTypedArray(),
			*completedTurn("turn_1", "d_2", "Second answer").toTypedArray(),
		)

		val reply = service.ask("and now?", "wrun_1")

		assertEquals(AgentReply("wrun_1", "Second answer"), reply)
		assertEquals(listOf(Duration.ofMillis(250), Duration.ofMillis(500)), sleeps)
	}

	@Test
	fun `gives up when a session never becomes ready`() {
		// Backoff 250ms, 500ms, 1s, then 2s steps: the 13th attempt is the last inside the 20s window
		repeat(13) {
			server.expect(requestTo("$AGENT/eve/v1/session/wrun_1"))
				.andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body(NOT_READY))
		}

		assertThrows<AgentTimeoutException> { service.ask("hi", "wrun_1") }
		assertEquals(12, sleeps.size)
		assertTrue(sleeps.sumOf { it.toMillis() } <= 20_000)
	}

	@Test
	fun `reports an ended session as not found`() {
		server.expect(requestTo("$AGENT/eve/v1/session/wrun_gone"))
			.andRespond(
				withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
					.body("""{"code":"session_not_active","error":"The session is no longer active.","ok":false}"""),
			)

		assertThrows<AgentSessionNotFoundException> { service.ask("hi", "wrun_gone") }
	}

	@Test
	fun `reports a failed turn with the model's error`() {
		expectCreate()
		expectStream(
			0,
			line(
				"""{"type":"model.settled","scope":{"turnId":"turn_0","runId":"run_0"},
				  "data":{"outcome":"failed","error":{"code":"MODEL_CALL_FAILED","message":"Missing ANTHROPIC_API_KEY"}}}""",
			),
			line(
				"""{"type":"turn.settled","scope":{"turnId":"turn_0"},"data":{"turnId":"turn_0","outcome":"failed"}},
				 {"type":"delivery.settled","data":{"deliveryId":"d_1","outcome":"handled","turnId":"turn_0"}}""",
			),
		)

		val ex = assertThrows<AgentTurnFailedException> { service.ask("hello") }
		assertEquals("The agent's turn failed: Missing ANTHROPIC_API_KEY", ex.message)
		assertEquals("wrun_1", ex.sessionId)
	}

	@Test
	fun `reports a delivery the agent did not handle`() {
		expectCreate()
		expectStream(0, line("""{"type":"delivery.settled","data":{"deliveryId":"d_1","outcome":"refused","reason":"not allowed"}}"""))

		val ex = assertThrows<AgentTurnFailedException> { service.ask("hello") }
		assertEquals("The agent did not handle the message (outcome refused): not allowed", ex.message)
	}

	@Test
	fun `fails when the stream ends before the delivery settles`() {
		expectCreate()
		expectStream(0, line("""{"type":"delivery.admitted","data":{"deliveryId":"d_1"}}"""), """{"${'$'}eve":"stream.ended"}""")

		assertThrows<AgentTurnFailedException> { service.ask("hello") }
	}

	@Test
	fun `maps a rejected token to an unavailable agent`() {
		server.expect(requestTo("$AGENT/eve/v1/session")).andRespond(withStatus(HttpStatus.UNAUTHORIZED))

		val ex = assertThrows<AgentUnavailableException> { service.ask("hello") }
		assertEquals(401, ex.upstreamStatus?.value())
		assertEquals("Failed to create a session: the agent rejected the API token", ex.message)
	}

	@Test
	fun `maps agent errors to an unavailable agent`() {
		server.expect(requestTo("$AGENT/eve/v1/session"))
			.andRespond(
				withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
					.body("""{"ok":false,"error":"warming up"}"""),
			)

		val ex = assertThrows<AgentUnavailableException> { service.ask("hello") }
		assertEquals("Failed to create a session: the agent answered 503 SERVICE_UNAVAILABLE: warming up", ex.message)
	}

	@Test
	fun `times out when the reply does not settle in time`() {
		// Every reading of the clock moves it a minute on, past the two-minute timeout after a few lines
		val ticking = object : Clock() {
			var now: Instant = Instant.parse("2026-11-03T12:00:00Z")
			override fun instant(): Instant = now.also { now = now.plus(Duration.ofMinutes(1)) }
			override fun getZone(): ZoneId = ZoneOffset.UTC
			override fun withZone(zone: ZoneId): Clock = this
		}
		val service = service(ticking)
		expectCreate()
		expectStream(0, HEARTBEAT, HEARTBEAT, HEARTBEAT, HEARTBEAT)

		val ex = assertThrows<AgentTimeoutException> { service.ask("hello") }
		assertEquals("wrun_1", ex.sessionId)
	}

	private fun expectCreate() {
		server.expect(requestTo("$AGENT/eve/v1/session"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(jsonPath("$.message").value("hello"))
			.andRespond(
				withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
					.body("""{"ok":true,"sessionId":"wrun_1","deliveryId":"d_1"}"""),
			)
	}

	private fun expectStream(startIndex: Int, vararg lines: String) {
		server.expect(requestTo("$AGENT/eve/v1/session/wrun_1/stream?startIndex=$startIndex"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(lines.joinToString("\n", postfix = "\n"), MediaType.parseMediaType("application/x-ndjson")))
	}

	private fun completedTurn(turnId: String, deliveryId: String, text: String) = listOf(
		line("""{"type":"delivery.consumed","data":{"deliveryId":"$deliveryId","turnId":"$turnId"}}"""),
		line("""{"type":"content.completed","scope":{"turnId":"$turnId"},"data":{"partId":"p","kind":"text","phase":"reply","value":"$text"}}"""),
		line(
			"""{"type":"turn.settled","scope":{"turnId":"$turnId"},"data":{"turnId":"$turnId","outcome":"completed"}},
			 {"type":"delivery.settled","data":{"deliveryId":"$deliveryId","outcome":"handled","turnId":"$turnId"}}""",
		),
	)

	/** One event line; NDJSON lines can't contain newlines. */
	private fun line(facts: String) = """{"at":"2026-11-03T12:00:00Z","facts":[${facts.replace(Regex("\\s*\\n\\s*"), "")}]}"""

	companion object {
		private const val AGENT = "http://agent.test"
		private const val HEARTBEAT = """{"${'$'}eve":"heartbeat"}"""
		private const val LEASE_ENDED = """{"${'$'}eve":"stream.lease-ended"}"""
		private const val PROGRESS = """{"progress":{"type":"content.delta","data":{"partId":"p","delta":"x"}}}"""
		private const val NOT_READY = """{"ok":false,"code":"session_not_ready","error":"not ready"}"""
	}
}
