package com.toptier.custom.feature.service

import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.checkout.exception.GitCommandException
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeService
import com.toptier.custom.feature.config.FeatureProperties
import com.toptier.custom.feature.domain.FeatureTaskRepository
import com.toptier.custom.feature.exception.FeatureWorktreeMissingException
import com.toptier.custom.feature.exception.RestackConflictException
import com.toptier.custom.feature.exception.RestackNotNeededException
import com.toptier.custom.feature.exception.TaskClosedException
import com.toptier.custom.feature.exception.TaskDirtyException
import com.toptier.custom.feature.exception.TaskNotFoundException
import com.toptier.custom.github.service.GitHubPullWriteClient
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Clock

/**
 * Rebuilds a task branch on its effective base after the branch below it moved or merged: rebases the task's own
 * commits, pushes with a lease, retargets its pull request, and records the new base. A conflict aborts the rebase
 * and leaves the branch as it was.
 */
@Service
class TaskRestacker(
	private val featureService: FeatureService,
	private val featureTasks: FeatureTasks,
	private val tasks: FeatureTaskRepository,
	private val checkouts: RepositoryCheckoutRepository,
	private val checkoutService: CheckoutService,
	private val worktreeService: WorktreeService,
	private val locks: CheckoutLocks,
	private val runner: GitRunner,
	private val pulls: GitHubPullWriteClient,
	private val props: FeatureProperties,
	private val clock: Clock,
) {

	/** Restacks task [number]. */
	fun restack(projectId: Long, featureId: Long, number: Int): TaskView {
		val feature = featureService.load(projectId, featureId)
		val rows = featureTasks.rows(feature)
		val task = rows.firstOrNull { it.number == number } ?: throw TaskNotFoundException()
		if (number == 0 || task.pullNumber == null) throw RestackNotNeededException(number)
		val statuses = featureTasks.statuses(projectId, rows)
		statuses.values.firstNotNullOfOrNull { it.error }?.let { throw it }
		val pull = statuses[number]?.status
		if (pull != null && pull.state != "open") throw TaskClosedException(number)

		val checkout = checkouts.findByProjectId(projectId) ?: throw CheckoutNotReadyException()
		val default = checkout.defaultBranch ?: "main"
		val effective = featureTasks.effectiveBase(number, rows, statuses, default)
		val baseRow = rows.firstOrNull { it.branch == task.baseBranch && it.number < number }
		if (!StackRules.needsRestack(task, effective, pull, baseRow, baseRow?.let { statuses[it.number]?.status })) {
			throw RestackNotNeededException(number)
		}
		val worktree = featureService.worktreeForBranch(projectId, task.branch)
		if (worktree == null || worktree.missing) throw FeatureWorktreeMissingException(task.branch)
		val dir = Path.of(worktree.path)
		if (runner.runOrThrow(dir, listOf("status", "--porcelain", "--untracked-files=all")).isNotBlank()) {
			throw TaskDirtyException(task.branch)
		}

		val newBaseHead = locks.withLock(checkout.id!!) {
			checkoutService.fetchUnderLock(checkout)
			val clone = Path.of(checkout.path)
			val baseRef = "origin/$effective"
			if (!runner.refExists(clone, "refs/remotes/$baseRef")) throw CheckoutNotReadyException()
			rebase(dir, baseRef, task.baseHead ?: baseRef)
			worktreeService.push(projectId, worktree.id, force = true)
			if (effective != task.baseBranch) {
				val repo = featureTasks.repository(projectId)
				pulls.setBase(repo.owner, repo.repo, task.pullNumber!!, effective)
			}
			runner.runOrThrow(clone, listOf("rev-parse", baseRef)).trim()
		}
		task.baseBranch = effective
		task.baseHead = newBaseHead
		task.updatedAt = clock.instant()
		tasks.saveAndFlush(task)
		return featureTasks.get(projectId, featureId, number)
	}

	/** Replays the commits after [upstream] onto [onto]; a conflict is aborted and reported with its files. */
	private fun rebase(dir: Path, onto: String, upstream: String) {
		val identity = listOf("-c", "user.name=${props.commitName}", "-c", "user.email=${props.commitEmail}", "-c", "commit.gpgsign=false")
		val result = runner.run(dir, identity + listOf("rebase", "--onto", onto, upstream))
		if (result.exitCode == 0) return
		val conflicts = runner.run(dir, listOf("diff", "--name-only", "--diff-filter=U")).stdout.lines().filter { it.isNotBlank() }
		runner.run(dir, listOf("rebase", "--abort"))
		if (conflicts.isNotEmpty()) throw RestackConflictException(conflicts)
		throw GitCommandException("rebase", result.exitCode, result.stderr)
	}
}
