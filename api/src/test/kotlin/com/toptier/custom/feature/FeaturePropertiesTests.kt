package com.toptier.custom.feature

import com.toptier.custom.feature.config.FeatureProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertEquals

class FeaturePropertiesTests {

	@Test
	fun defaults() {
		val props = FeatureProperties()

		assertEquals("factory", props.commitName)
		assertEquals("factory@users.noreply.github.com", props.commitEmail)
		assertEquals(Duration.ofMinutes(5), props.chatIdleTimeout)
	}

	@Test
	fun `invalid values are refused`() {
		assertThrows<IllegalArgumentException> { FeatureProperties(commitName = " ") }
		assertThrows<IllegalArgumentException> { FeatureProperties(commitEmail = "factory") }
		assertThrows<IllegalArgumentException> { FeatureProperties(chatIdleTimeout = Duration.ZERO) }
	}
}
