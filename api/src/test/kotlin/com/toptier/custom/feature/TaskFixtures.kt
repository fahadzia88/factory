package com.toptier.custom.feature

import com.toptier.custom.feature.domain.FeatureTask
import com.toptier.custom.github.service.CheckRunStatus
import com.toptier.custom.github.service.ChecksStatus
import com.toptier.custom.github.service.PullStatus
import java.time.Instant

/** Task rows and pull request statuses for task tests. */
object TaskFixtures {

	val AT: Instant = Instant.parse("2026-11-10T10:00:00Z")

	fun task(number: Int, branch: String, baseBranch: String, baseHead: String? = null, pullNumber: Int? = null, featureId: Long = 1) =
		FeatureTask().apply {
			this.featureId = featureId
			this.number = number
			layer = if (number == 0) "docs" else "api"
			slug = if (number == 0) "docs" else "task-$number"
			this.branch = branch
			this.baseBranch = baseBranch
			this.baseHead = baseHead
			this.pullNumber = pullNumber
			createdAt = AT
			updatedAt = AT
		}

	fun pull(number: Int, state: String = "open", headSha: String = "sha$number", baseRef: String = "main", checks: ChecksStatus = ChecksStatus("none", emptyList())) =
		PullStatus(
			number = number,
			title = "PR $number",
			state = state,
			draft = false,
			htmlUrl = "https://github.com/acme/factory/pull/$number",
			baseRef = baseRef,
			headSha = headSha,
			mergedAt = if (state == "merged") AT else null,
			checks = checks,
		)

	fun failingChecks() = ChecksStatus(
		"failure",
		listOf(CheckRunStatus("test", "completed", "failure", "https://github.com/acme/factory/runs/1", "2 tests failed", "FooTest")),
	)
}
