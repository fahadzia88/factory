package com.toptier.custom.feature.service

import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.checkout.service.WorktreeInspector
import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.domain.FeatureStepRepository
import com.toptier.custom.feature.exception.FeatureNotFoundException
import com.toptier.custom.project.service.ProjectService
import org.springframework.stereotype.Service
import java.nio.file.Path

/** Reads a project's features, each with its steps, stored workflow and worktree. */
@Service
class FeatureService(
	private val projects: ProjectService,
	private val features: FeatureRepository,
	private val steps: FeatureStepRepository,
	private val workflowCodec: WorkflowCodec,
	private val checkouts: RepositoryCheckoutRepository,
	private val worktrees: RepositoryWorktreeRepository,
	private val inspector: WorktreeInspector,
) {

	/** The project's features, newest number first. */
	fun list(projectId: Long): List<Feature> {
		projects.get(projectId)
		return features.findByProjectIdOrderByNumberDesc(projectId)
	}

	/** The feature with its steps, workflow and worktree. */
	fun get(projectId: Long, featureId: Long): FeatureView = view(load(projectId, featureId))

	/** The feature, when it belongs to the project; another project's feature is not found. */
	fun load(projectId: Long, featureId: Long): Feature {
		projects.get(projectId)
		return features.findByIdAndProjectId(featureId, projectId) ?: throw FeatureNotFoundException()
	}

	/** [feature] with its stored workflow, its steps in order and its worktree. */
	fun view(feature: Feature): FeatureView = FeatureView(
		feature = feature,
		workflow = workflowCodec.read(feature.workflow),
		steps = steps.findByFeatureIdOrderByPositionAsc(requireNotNull(feature.id) { "Feature has not been saved" }),
		worktree = worktreeOf(feature),
	)

	/** The worktree of the feature's branch in a ready checkout, or null when there's none. */
	private fun worktreeOf(feature: Feature): FeatureWorktree? {
		val checkout = checkouts.findByProjectId(feature.projectId)?.takeIf { it.state == CheckoutState.READY } ?: return null
		val row = worktrees.findByCheckoutIdAndBranch(checkout.id!!, feature.branch) ?: return null
		return FeatureWorktree(row.id!!, row.path, inspector.missing(Path.of(row.path)))
	}
}
