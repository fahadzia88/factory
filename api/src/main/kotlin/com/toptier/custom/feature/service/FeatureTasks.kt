package com.toptier.custom.feature.service

import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureTask
import com.toptier.custom.feature.domain.FeatureTaskRepository
import com.toptier.custom.feature.exception.TaskNotFoundException
import com.toptier.custom.github.service.GitHubPullWriteClient
import com.toptier.custom.github.service.PullStatus
import com.toptier.custom.project.service.GitHubRepositoryUrl
import com.toptier.custom.project.service.ProjectService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Clock

/** A task with its worktree, its pull request (or why it couldn't be read) and whether it needs a restack. */
data class TaskView(
	val task: FeatureTask,
	val worktree: FeatureWorktree?,
	val pull: PullStatus?,
	val pullError: Problem?,
	val needsRestack: Boolean,
)

/** A task's pull request as read from GitHub: the status, or the failure. Both null when it has no PR. */
data class TaskPull(val status: PullStatus?, val error: Exception?)

/** Reads a feature's stack of task branches and their pull requests. */
@Service
class FeatureTasks(
	private val featureService: FeatureService,
	private val tasks: FeatureTaskRepository,
	private val projects: ProjectService,
	private val checkouts: RepositoryCheckoutRepository,
	private val pulls: GitHubPullWriteClient,
	private val clock: Clock,
) {

	/** Every task of the feature, row 0 first. */
	fun list(projectId: Long, featureId: Long): List<TaskView> {
		val feature = featureService.load(projectId, featureId)
		val rows = rows(feature)
		return views(feature, rows, statuses(projectId, rows))
	}

	/** One task; a GitHub failure on its own pull request is thrown, not reported as `pullError`. */
	fun get(projectId: Long, featureId: Long, number: Int): TaskView {
		val feature = featureService.load(projectId, featureId)
		val rows = rows(feature)
		if (rows.none { it.number == number }) throw TaskNotFoundException()
		val statuses = statuses(projectId, rows)
		statuses[number]?.error?.let { throw it }
		return views(feature, rows, statuses).single { it.task.number == number }
	}

	/** The feature's rows, creating row 0 (the docs branch) on first use. */
	fun rows(feature: Feature): List<FeatureTask> {
		ensureDocsRow(feature)
		return tasks.findByFeatureIdOrderByNumberAsc(feature.id!!)
	}

	/** Creates row 0 for the feature's docs branch unless it exists; a concurrent create's row wins. */
	fun ensureDocsRow(feature: Feature): FeatureTask {
		tasks.findByFeatureIdAndNumber(feature.id!!, 0)?.let { return it }
		val now = clock.instant()
		return try {
			tasks.saveAndFlush(FeatureTask().apply {
				featureId = feature.id!!
				number = 0
				layer = "docs"
				slug = "docs"
				branch = feature.branch
				baseBranch = defaultBranch(feature.projectId)
				createdAt = now
				updatedAt = now
			})
		} catch (_: DataIntegrityViolationException) {
			tasks.findByFeatureIdAndNumber(feature.id!!, 0)!!
		}
	}

	/** Each row's pull request status; a GitHub failure is kept per row so the others still load. */
	fun statuses(projectId: Long, rows: List<FeatureTask>): Map<Int, TaskPull> {
		val withPulls = rows.filter { it.pullNumber != null }
		if (withPulls.isEmpty()) return emptyMap()
		val repo = repository(projectId)
		return withPulls.associate { row ->
			row.number to try {
				TaskPull(pulls.status(repo.owner, repo.repo, row.pullNumber!!), null)
			} catch (ex: Exception) {
				if (!PullProblems.covers(ex)) throw ex
				TaskPull(null, ex)
			}
		}
	}

	/** The project's repository on GitHub. */
	fun repository(projectId: Long): GitHubRepositoryUrl {
		val project = projects.get(projectId)
		return checkNotNull(GitHubRepositoryUrl.parse(project.repositoryUrl)) { "Stored URL of project $projectId is invalid" }
	}

	/** The checkout's default branch; `main` before a checkout has one. */
	fun defaultBranch(projectId: Long): String = checkouts.findByProjectId(projectId)?.defaultBranch ?: "main"

	/** The branch task [number] should be built on now. */
	fun effectiveBase(number: Int, rows: List<FeatureTask>, statuses: Map<Int, TaskPull>, defaultBranch: String): String =
		StackRules.effectiveBase(number, rows, { statuses[it]?.status?.state == "merged" }, defaultBranch)

	private fun views(feature: Feature, rows: List<FeatureTask>, statuses: Map<Int, TaskPull>): List<TaskView> {
		val default = defaultBranch(feature.projectId)
		// Without every lower pull request the effective base is a guess, so no restack is suggested
		val unreadable = statuses.filterValues { it.error != null }.keys
		return rows.map { row ->
			val pull = statuses[row.number]
			val baseRow = rows.firstOrNull { it.branch == row.baseBranch && it.number < row.number }
			val needsRestack = unreadable.none { it < row.number } &&
				StackRules.needsRestack(
					row,
					effectiveBase(row.number, rows, statuses, default),
					pull?.status,
					baseRow,
					baseRow?.let { statuses[it.number]?.status },
				)
			TaskView(
				task = row,
				worktree = featureService.worktreeForBranch(feature.projectId, row.branch),
				pull = pull?.status,
				pullError = pull?.error?.let(PullProblems::describe),
				needsRestack = needsRestack,
			)
		}
	}
}
