package com.toptier.custom.feature

import com.toptier.custom.feature.service.PullText
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PullTextTests {

	@Test
	fun titles() {
		assertEquals("006 03 api: Task records", PullText.title(6, 3, "api", "Task records"))
		assertEquals("006 Feature implementation: docs", PullText.docsTitle(6, "Feature implementation"))
	}

	@Test
	fun `body with and without the pull request below`() {
		val spec = "https://github.com/acme/factory/blob/feat/006-x/00-docs/feature/006-x/tasks/03-api-y/spec.md"

		assertEquals(
			"Task records\n\nSpec: $spec\nACs covered: AC-3, AC-4\n",
			PullText.body("Task records", spec, listOf("AC-3", "AC-4"), null),
		)
		assertEquals(
			"Task records\n\nSpec: $spec\nACs covered: AC-3\n\nStacked on #60\n",
			PullText.body("Task records", spec, listOf("AC-3"), 60),
		)
		assertEquals(
			"Docs for 006-x: requirements, task breakdown and task specs.\n\nFeature folder: https://x\n",
			PullText.docsBody("006-x", "https://x"),
		)
	}

	@Test
	fun `blob urls keep slashes in branch names`() {
		assertEquals(
			"https://github.com/acme/factory/blob/feat/006-x/00-docs/feature/006-x",
			PullText.blobUrl("acme", "factory", "feat/006-x/00-docs", "feature/006-x"),
		)
	}
}
