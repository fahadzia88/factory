package com.toptier.custom.feature.service

import com.toptier.custom.checkout.service.WorktreeService
import com.toptier.custom.feature.exception.TasksUnfinishedException
import com.toptier.custom.feature.exception.UnfinishedTask
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path

/** A task worktree that completing the feature couldn't remove, and why. */
data class KeptWorktree(val branch: String, val reason: String)

/** A completed feature, with the task worktrees that stayed. */
data class CompletedFeature(val view: FeatureView, val kept: List<KeptWorktree>)

/**
 * Finishes a feature once every task (the docs branch included) has a merged pull request: sets it to `done` and removes
 * the task worktrees. The docs worktree and the task rows stay.
 */
@Service
class FeatureCompleter(
	private val featureService: FeatureService,
	private val featureTasks: FeatureTasks,
	private val featureProgress: FeatureProgress,
	private val worktreeService: WorktreeService,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun complete(projectId: Long, featureId: Long): CompletedFeature {
		val feature = featureService.load(projectId, featureId)
		val rows = featureTasks.rows(feature)
		val docs = featureService.worktreeForBranch(projectId, feature.branch)
		val specs = if (docs != null && !docs.missing) TaskSpecs.scan(Path.of(docs.path), feature.folder) else emptyList()

		val statuses = featureTasks.statuses(projectId, rows)
		statuses.values.firstNotNullOfOrNull { it.error }?.let { throw it }
		val unfinished = buildList {
			for (spec in specs) if (rows.none { it.number == spec.number }) add(UnfinishedTask(spec.number, "no task started"))
			for (row in rows) {
				val state = statuses[row.number]?.status?.state
				when {
					row.pullNumber == null -> add(UnfinishedTask(row.number, "no pull request"))
					state != "merged" -> add(UnfinishedTask(row.number, "pull request not merged ($state)"))
				}
			}
		}.sortedBy { it.task }
		if (unfinished.isNotEmpty()) throw TasksUnfinishedException(unfinished)

		featureProgress.setStatus(projectId, featureId, DONE)
		val kept = rows.filter { it.number > 0 }.mapNotNull { row ->
			val worktree = featureService.worktreeForBranch(projectId, row.branch) ?: return@mapNotNull null
			try {
				worktreeService.remove(projectId, worktree.id, force = false)
				null
			} catch (ex: Exception) {
				log.info("Feature {}: kept the worktree of {}: {}", featureId, row.branch, ex.message)
				KeptWorktree(row.branch, ex.message ?: "It couldn't be removed.")
			}
		}
		return CompletedFeature(featureService.get(projectId, featureId), kept)
	}

	private companion object {
		const val DONE = "done"
	}
}
