package com.toptier.custom.feature

import com.toptier.custom.feature.TaskFixtures.pull
import com.toptier.custom.feature.TaskFixtures.task
import com.toptier.custom.feature.service.StackRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StackRulesTests {

	private val rows = listOf(
		task(0, "feat/x/00-docs", "main"),
		task(1, "feat/x/01-api-a", "feat/x/00-docs"),
		task(2, "feat/x/02-app-b", "feat/x/01-api-a"),
	)

	@Test
	fun `the effective base is the nearest lower branch that isn't merged`() {
		assertEquals("feat/x/01-api-a", StackRules.effectiveBase(2, rows, { false }, "main"))
		assertEquals("feat/x/00-docs", StackRules.effectiveBase(2, rows, { it == 1 }, "main"))
		assertEquals("feat/x/01-api-a", StackRules.effectiveBase(2, rows, { it == 0 }, "main"))
		assertEquals("main", StackRules.effectiveBase(2, rows, { true }, "main"))
		assertEquals("main", StackRules.effectiveBase(0, rows, { false }, "main"))
	}

	@Test
	fun `a task needs a restack when its base merged or the base's head moved`() {
		val task = task(2, "feat/x/02-app-b", "feat/x/01-api-a", baseHead = "old")
		val base = rows[1]

		assertTrue(StackRules.needsRestack(task, "feat/x/00-docs", pull(12), base, pull(11, state = "merged")))
		assertTrue(StackRules.needsRestack(task, "feat/x/01-api-a", pull(12), base, pull(11, headSha = "new")))
		assertTrue(StackRules.needsRestack(task, "feat/x/01-api-a", null, base, pull(11, headSha = "new")))
		assertFalse(StackRules.needsRestack(task, "feat/x/01-api-a", pull(12), base, pull(11, headSha = "old")))
	}

	@Test
	fun `merged and closed tasks never need a restack, nor without the base's pull data`() {
		val task = task(2, "feat/x/02-app-b", "feat/x/01-api-a", baseHead = "old")

		assertFalse(StackRules.needsRestack(task, "feat/x/00-docs", pull(12, state = "merged"), rows[1], pull(11, headSha = "new")))
		assertFalse(StackRules.needsRestack(task, "feat/x/00-docs", pull(12, state = "closed"), rows[1], pull(11, headSha = "new")))
		assertFalse(StackRules.needsRestack(task, "feat/x/01-api-a", pull(12), rows[1], null))
	}
}
