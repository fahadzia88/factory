package com.toptier.custom.feature.service

import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.feature.domain.FeatureTaskRepository
import com.toptier.custom.feature.exception.FeatureWorktreeMissingException
import com.toptier.custom.feature.exception.InvalidFeatureRequestException
import com.toptier.custom.feature.exception.InvalidStatusException
import com.toptier.custom.feature.exception.PullExistsException
import com.toptier.custom.feature.exception.TaskNotFoundException
import com.toptier.custom.feature.exception.TaskNotPushedException
import com.toptier.custom.github.service.GitHubPullWriteClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Clock

/** Opens a feature's pull requests: one per task, and the docs pull request for row 0. */
@Service
class TaskPulls(
	private val featureService: FeatureService,
	private val featureTasks: FeatureTasks,
	private val featureProgress: FeatureProgress,
	private val tasks: FeatureTaskRepository,
	private val checkouts: RepositoryCheckoutRepository,
	private val checkoutService: CheckoutService,
	private val locks: CheckoutLocks,
	private val runner: GitRunner,
	private val pulls: GitHubPullWriteClient,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * Opens task [number]'s pull request into its effective base. [task] and [acs] name the task and the ACs it
	 * covers; row 0 (the docs branch) takes neither. Sets the task's step to `pr-open`.
	 */
	fun open(projectId: Long, featureId: Long, number: Int, task: String?, acs: List<String>?): TaskView {
		val feature = featureService.load(projectId, featureId)
		val rows = featureTasks.rows(feature)
		val row = rows.firstOrNull { it.number == number } ?: throw TaskNotFoundException()
		if (row.pullNumber != null) throw PullExistsException(number)
		val name = task?.trim().orEmpty()
		val covered = acs.orEmpty().map { it.trim() }
		if (number > 0) {
			if (name.isEmpty() || name.length > 100) throw InvalidFeatureRequestException("task must be 1 to 100 characters.")
			if (covered.isEmpty()) throw InvalidFeatureRequestException("acs must list at least one AC, like AC-3.")
			covered.firstOrNull { !AC.matches(it) }?.let { throw InvalidFeatureRequestException("acs has \"$it\", which isn't like AC-3.") }
		}

		val worktree = featureService.worktreeForBranch(projectId, row.branch)
		if (worktree == null || worktree.missing) throw FeatureWorktreeMissingException(row.branch)
		val checkout = checkouts.findByProjectId(projectId) ?: throw CheckoutNotReadyException()
		val pushed = locks.withLock(checkout.id!!) {
			checkoutService.fetchUnderLock(checkout)
			runner.refExists(Path.of(checkout.path), "refs/remotes/origin/${row.branch}")
		}
		if (!pushed) throw TaskNotPushedException(row.branch)

		val statuses = featureTasks.statuses(projectId, rows)
		val base = featureTasks.effectiveBase(number, rows, statuses, checkout.defaultBranch ?: "main")
		val stackedOn = rows.firstOrNull { it.branch == base && it.number < number }?.pullNumber
		val repo = featureTasks.repository(projectId)
		val title: String
		val body: String
		if (number == 0) {
			title = PullText.docsTitle(feature.number, feature.name)
			body = PullText.docsBody(FeatureNaming.code(feature.number, feature.slug), PullText.blobUrl(repo.owner, repo.repo, feature.branch, feature.folder))
		} else {
			title = PullText.title(feature.number, number, row.layer, name)
			val specUrl = PullText.blobUrl(repo.owner, repo.repo, feature.branch, row.specPath ?: feature.folder)
			body = PullText.body(name, specUrl, covered, stackedOn)
		}

		val created = pulls.create(repo.owner, repo.repo, title, row.branch, base, body)
		row.pullNumber = created.number
		row.updatedAt = clock.instant()
		tasks.saveAndFlush(row)
		row.stepId?.let { stepId ->
			try {
				featureProgress.updateStep(projectId, featureId, stepId, StepChanges(status = "pr-open"))
			} catch (ex: InvalidStatusException) {
				log.warn("Feature {}: step {} has no pr-open state: {}", featureId, stepId, ex.message)
			}
		}
		return featureTasks.get(projectId, featureId, number)
	}

	/** Opens the docs pull request (row 0) into the default branch. */
	fun openDocs(projectId: Long, featureId: Long): TaskView = open(projectId, featureId, 0, null, null)

	private companion object {
		val AC = Regex("^AC-\\d+$")
	}
}
