package com.toptier.custom.feature.service

import com.toptier.custom.feature.domain.FeatureTask
import com.toptier.custom.github.service.PullStatus

/** How a feature's task branches stack: what each is based on, and when it has to be rebuilt. */
object StackRules {

	/** The branch of the nearest lower task whose pull request isn't merged (no PR counts as not merged), else [defaultBranch]. */
	fun effectiveBase(number: Int, rows: List<FeatureTask>, merged: (Int) -> Boolean, defaultBranch: String): String {
		for (n in number - 1 downTo 0) {
			val row = rows.firstOrNull { it.number == n } ?: continue
			if (!merged(n)) return row.branch
		}
		return defaultBranch
	}

	/**
	 * True for a task whose PR isn't merged or closed when its recorded base is no longer [effectiveBase], or its base is
	 * [baseRow]'s branch and that branch's PR head moved since. Without the lower pull data it's never true.
	 */
	fun needsRestack(task: FeatureTask, effectiveBase: String, pull: PullStatus?, baseRow: FeatureTask?, baseRowPull: PullStatus?): Boolean {
		if (pull != null && pull.state != "open") return false
		if (task.baseBranch != effectiveBase) return true
		if (baseRow == null || baseRowPull?.headSha == null) return false
		return baseRowPull.headSha != task.baseHead
	}
}
