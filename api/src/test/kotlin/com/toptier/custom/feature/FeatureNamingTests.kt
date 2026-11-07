package com.toptier.custom.feature

import com.toptier.custom.feature.exception.InvalidFeatureNameException
import com.toptier.custom.feature.service.FeatureNaming
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class FeatureNamingTests {

	@Test
	fun `slugs are lowercase words joined by dashes`() {
		assertEquals("feature-records", FeatureNaming.slug("Feature records"))
		assertEquals("n-code-spaces", FeatureNaming.slug("  Ünïcode & spaces!! "))
	}

	@Test
	fun `long slugs are cut to 40 characters without a trailing dash`() {
		val slug = FeatureNaming.slug("a".repeat(39) + " " + "b".repeat(20))

		assertEquals("a".repeat(39), slug)
		assertEquals(40, FeatureNaming.slug("x".repeat(60)).length)
	}

	@Test
	fun `names are trimmed`() {
		assertEquals("Feature records", FeatureNaming.name("  Feature records "))
	}

	@Test
	fun `bad names are refused`() {
		assertRefused(null, "Enter a name.")
		assertRefused("", "Enter a name.")
		assertRefused("   ", "Enter a name.")
		assertRefused("x".repeat(101), "Keep the name to 100 characters or fewer.")
		assertRefused("!!!", "The name needs at least one letter or digit.")
	}

	@Test
	fun `the next number is past records and folders`() {
		assertEquals(7, FeatureNaming.nextNumber(4, listOf("feature/006-x", "feature/_templates", "feature/rules.md")))
		assertEquals(5, FeatureNaming.nextNumber(4, listOf("003-y")))
		assertEquals(1, FeatureNaming.nextNumber(null, emptyList()))
	}

	@Test
	fun `code, branch and folder`() {
		val code = FeatureNaming.code(7, "x")

		assertEquals("007-x", code)
		assertEquals("feat/007-x/00-docs", FeatureNaming.branch(code))
		assertEquals("feature/007-x", FeatureNaming.folder(code))
	}

	private fun assertRefused(name: String?, message: String) {
		val ex = assertThrows<InvalidFeatureNameException> { FeatureNaming.name(name) }
		assertEquals(message, ex.message)
	}
}
