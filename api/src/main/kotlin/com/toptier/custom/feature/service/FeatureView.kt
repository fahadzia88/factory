package com.toptier.custom.feature.service

import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureStep

/** A feature with its stored workflow, its steps in order, and its worktree; [worktree] is null when none has its branch. */
data class FeatureView(
	val feature: Feature,
	val workflow: Workflow,
	val steps: List<FeatureStep>,
	val worktree: FeatureWorktree?,
)

/** The worktree of a feature's branch; [missing] when its folder isn't on disk. */
data class FeatureWorktree(val id: Long, val path: String, val missing: Boolean)
