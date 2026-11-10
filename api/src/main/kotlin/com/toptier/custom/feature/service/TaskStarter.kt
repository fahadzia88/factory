package com.toptier.custom.feature.service

import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktree
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeService
import com.toptier.custom.feature.domain.FeatureStepRepository
import com.toptier.custom.feature.domain.FeatureTask
import com.toptier.custom.feature.domain.FeatureTaskRepository
import com.toptier.custom.feature.exception.FeatureWorktreeMissingException
import com.toptier.custom.feature.exception.InvalidStatusException
import com.toptier.custom.feature.exception.InvalidStepTypeException
import com.toptier.custom.feature.exception.InvalidTaskException
import com.toptier.custom.feature.exception.PreviousTaskUnfinishedException
import com.toptier.custom.feature.exception.SpecsNotApprovedException
import com.toptier.custom.feature.exception.TaskExistsException
import com.toptier.custom.feature.exception.TaskOutOfOrderException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * Starts a feature's next implementation task: checks that every spec is approved and the stack order, adds the
 * task's worktree from its effective base, records the task and its step, and moves the feature to `implementation`.
 * A failure after the worktree exists undoes it.
 */
@Service
class TaskStarter(
	private val featureService: FeatureService,
	private val featureTasks: FeatureTasks,
	private val featureProgress: FeatureProgress,
	private val tasks: FeatureTaskRepository,
	private val steps: FeatureStepRepository,
	private val checkouts: RepositoryCheckoutRepository,
	private val checkoutService: CheckoutService,
	private val worktreeService: WorktreeService,
	private val locks: CheckoutLocks,
	private val runner: GitRunner,
	private val codec: WorkflowCodec,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/** Starts the task in folder [dir] (`NN-<layer>-<slug>`) of the feature's `tasks/`. */
	fun start(projectId: Long, featureId: Long, dir: String?): TaskView {
		val feature = featureService.load(projectId, featureId)
		val docs = featureService.worktreeForBranch(projectId, feature.branch)
		if (docs == null || docs.missing) throw FeatureWorktreeMissingException(feature.branch)
		val docsDir = Path.of(docs.path)

		val task = TaskDir.parse(dir?.trim()) ?: throw InvalidTaskException("\"${dir ?: ""}\" isn't a task folder name like 01-api-task-records.")
		val specPath = "${feature.folder}/tasks/${task.name}/spec.md"
		if (!Files.isRegularFile(docsDir.resolve(specPath))) throw InvalidTaskException("$specPath doesn't exist.")
		val unapproved = TaskSpecs.scan(docsDir, feature.folder).filter { it.status != "approved" }.map { it.specPath }
		if (unapproved.isNotEmpty()) throw SpecsNotApprovedException(unapproved)

		featureTasks.ensureDocsRow(feature)
		val rows = tasks.findByFeatureIdOrderByNumberAsc(featureId)
		if (rows.any { it.number == task.number }) throw TaskExistsException(task.number)
		val expected = rows.maxOf { it.number } + 1
		if (task.number != expected) throw TaskOutOfOrderException(expected)
		val below = rows.single { it.number == task.number - 1 }
		if (below.pullNumber == null) throw PreviousTaskUnfinishedException(below.number)

		val workflow = codec.read(feature.workflow)
		if (workflow.step(STEP) == null) throw InvalidStepTypeException(STEP, workflow.steps.map { it.key })
		val moveFeature = feature.status != STEP && feature.status != "done"
		if (moveFeature && STEP !in workflow.featureStates) throw InvalidStatusException(STEP, workflow.featureStates)

		val checkout = checkouts.findByProjectId(projectId) ?: throw CheckoutNotReadyException()
		val default = checkout.defaultBranch ?: "main"
		val base = featureTasks.effectiveBase(task.number, rows, featureTasks.statuses(projectId, rows), default)
		val branch = "feat/${FeatureNaming.code(feature.number, feature.slug)}/${task.name}"

		locks.withLock(checkout.id!!) {
			checkoutService.fetchUnderLock(checkout)
			val worktree = worktreeService.add(projectId, branch, base).worktree
			val clone = Path.of(checkout.path)
			var saved: FeatureTask? = null
			try {
				val baseRef = if (runner.refExists(clone, "refs/remotes/origin/$base")) "origin/$base" else base
				val now = clock.instant()
				val row = tasks.saveAndFlush(FeatureTask().apply {
					this.featureId = featureId
					number = task.number
					layer = task.layer
					slug = task.slug
					this.branch = branch
					baseBranch = base
					baseHead = runner.runOrThrow(clone, listOf("rev-parse", baseRef)).trim()
					this.specPath = specPath
					createdAt = now
					updatedAt = now
				})
				saved = row
				val (step, _) = featureProgress.addStep(
					projectId, featureId, STEP, "Task %02d: %s %s".format(task.number, task.layer, task.slug), specPath,
				)
				row.stepId = step.id
				tasks.saveAndFlush(row)
				if (moveFeature) featureProgress.setStatus(projectId, featureId, STEP)
			} catch (ex: Exception) {
				undo(projectId, clone, worktree, saved)
				throw ex
			}
		}
		return featureTasks.get(projectId, featureId, task.number)
	}

	/** Removes the task's step, row, worktree and local branch; each failure is logged, not thrown. */
	private fun undo(projectId: Long, clone: Path, worktree: RepositoryWorktree, saved: FeatureTask?) {
		val branch = worktree.branch
		saved?.let { row ->
			attempt("delete the step of $branch") { row.stepId?.let(steps::deleteById) }
			attempt("delete the task row of $branch") { tasks.delete(row) }
		}
		attempt("remove the worktree of $branch") { worktreeService.remove(projectId, worktree.id!!, force = true) }
		attempt("delete the local branch $branch") {
			if (runner.refExists(clone, "refs/heads/$branch")) runner.runOrThrow(clone, listOf("branch", "-D", branch))
		}
	}

	private fun attempt(what: String, block: () -> Unit) {
		try {
			block()
		} catch (ex: Exception) {
			log.warn("Undoing a failed task start: couldn't {}: {}", what, ex.message)
		}
	}

	private companion object {
		const val STEP = "implementation"
	}
}
