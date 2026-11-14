package com.toptier.custom.homelab

import com.toptier.custom.homelab.config.HomelabProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomelabPropertiesTests {

	@Test
	fun `has the documented defaults`() {
		val props = HomelabProperties()

		assertEquals("", props.token)
		assertEquals(Duration.ofSeconds(10), props.timeout)
		assertEquals(Duration.ofSeconds(30), props.statusCache)
		assertEquals(Duration.ofSeconds(1), props.watchBackoffInitial)
		assertEquals(Duration.ofSeconds(30), props.watchBackoffMax)
	}

	@Test
	fun `rejects durations that are not positive`() {
		assertThrows<IllegalArgumentException> { HomelabProperties(timeout = Duration.ZERO) }
		assertThrows<IllegalArgumentException> { HomelabProperties(statusCache = Duration.ofSeconds(-1)) }
		assertThrows<IllegalArgumentException> { HomelabProperties(watchBackoffInitial = Duration.ZERO) }
	}

	@Test
	fun `rejects a maximum backoff shorter than the first`() {
		assertThrows<IllegalArgumentException> {
			HomelabProperties(watchBackoffInitial = Duration.ofSeconds(5), watchBackoffMax = Duration.ofSeconds(1))
		}
	}

	@Test
	fun `never prints the token`() {
		val text = HomelabProperties(token = "super-secret-token").toString()

		assertFalse(text.contains("super-secret-token"))
		assertTrue(text.contains("<hidden>"))
		assertTrue(HomelabProperties().toString().contains("<not set>"))
	}
}
