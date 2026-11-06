package com.toptier.custom.checkout.service

import com.toptier.custom.checkout.config.GitProperties
import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckout
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktree
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.checkout.exception.CheckoutExistsException
import com.toptier.custom.checkout.exception.CheckoutNotFoundException
import com.toptier.custom.checkout.exception.GitCommandException
import com.toptier.custom.checkout.exception.GitException
import com.toptier.custom.checkout.exception.GitFailure
import com.toptier.custom.checkout.exception.GitNotConfiguredException
import com.toptier.custom.checkout.exception.GitTimeoutException
import com.toptier.custom.checkout.exception.describe
import com.toptier.custom.project.service.ProjectService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.FileSystemUtils
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/** The last commit of a worktree's branch. */
data class HeadCommit(val sha: String, val subject: String)

/** A worktree with what's read from disk: whether its folder is [missing], and its [head]. */
data class WorktreeView(val worktree: RepositoryWorktree, val missing: Boolean, val head: HeadCommit?)

/** A checkout with its worktrees, main first; empty unless the checkout is ready. */
data class CheckoutView(val checkout: RepositoryCheckout, val worktrees: List<WorktreeView>)

/**
 * Clones a project's repository onto the shared volume in the background and reports its state.
 * Deliberately not `@Transactional`: no transaction stays open across a git call, and a clone
 * starts only after its `CLONING` row is committed.
 */
@Service
class CheckoutService(
	private val projects: ProjectService,
	private val checkouts: RepositoryCheckoutRepository,
	private val worktrees: RepositoryWorktreeRepository,
	private val runner: GitRunner,
	private val paths: CheckoutPaths,
	private val props: GitProperties,
	private val locks: CheckoutLocks,
	@Qualifier("checkoutExecutor") private val executor: TaskExecutor,
	transactionManager: PlatformTransactionManager,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(CheckoutService::class.java)

	private val transaction = TransactionTemplate(transactionManager)

	/** The project's checkout; throws `ProjectNotFoundException` or [CheckoutNotFoundException]. */
	fun get(projectId: Long): CheckoutView {
		projects.get(projectId)
		val checkout = checkouts.findByProjectId(projectId) ?: throw CheckoutNotFoundException()
		return view(checkout)
	}

	/**
	 * Starts cloning the project's repository and returns the `CLONING` checkout; the clone runs on
	 * the checkout executor. A `FAILED` checkout is reused (Retry); a cloning or ready one throws
	 * [CheckoutExistsException]. A blank git token throws [GitNotConfiguredException] before anything is written.
	 */
	fun start(projectId: Long): CheckoutView {
		val project = projects.get(projectId)
		runner.requireToken()
		val now = clock.instant()
		val existing = checkouts.findByProjectId(projectId)
		val checkout = if (existing != null) {
			if (existing.state != CheckoutState.FAILED) throw CheckoutExistsException()
			worktrees.deleteByCheckoutId(existing.id!!)
			existing.apply {
				state = CheckoutState.CLONING
				error = null
				defaultBranch = null
				lastFetchedAt = null
				createdAt = now
			}
		} else {
			RepositoryCheckout().apply {
				this.projectId = projectId
				path = paths.checkoutDir(projectId).toString()
				state = CheckoutState.CLONING
				createdAt = now
			}
		}
		val saved = try {
			checkouts.saveAndFlush(checkout)
		} catch (_: DataIntegrityViolationException) {
			throw CheckoutExistsException()
		}
		val id = saved.id!!
		val url = project.repositoryUrl
		val row = try {
			executor.execute { clone(id, url) }
			saved
		} catch (_: TaskRejectedException) {
			log.warn("Checkout {}: clone rejected, the clone queue is full", id)
			saved.state = CheckoutState.FAILED
			saved.error = "Too many clones are running. Retry in a minute."
			checkouts.saveAndFlush(saved)
		}
		return view(row)
	}

	/**
	 * Clones [url] into the checkout's folder and marks it `READY` with its main worktree, or
	 * `FAILED` with the reason in plain words. Does nothing unless the checkout is `CLONING`.
	 * Public so tests can run it directly.
	 */
	fun clone(checkoutId: Long, url: String) {
		locks.withLock(checkoutId) {
			val checkout = checkouts.findById(checkoutId).orElse(null)
			if (checkout == null || checkout.state != CheckoutState.CLONING) return@withLock
			val dir = paths.checkoutDir(checkout.projectId)
			val trees = paths.worktreesDir(checkout.projectId)
			try {
				deleteFolders(dir, trees)
				Files.createDirectories(paths.root)
				runner.runOrThrow(null, listOf("clone", "--", url, dir.toString()), network = true, timeout = props.cloneTimeout)
				val branch = runner.runOrThrow(dir, listOf("symbolic-ref", "--short", "HEAD")).trim()
				transaction.executeWithoutResult {
					val row = checkouts.findById(checkoutId).orElse(null)
					if (row == null) {
						deleteFolders(dir, trees)
						return@executeWithoutResult
					}
					val now = clock.instant()
					row.state = CheckoutState.READY
					row.defaultBranch = branch
					row.lastFetchedAt = now
					row.error = null
					checkouts.save(row)
					worktrees.save(RepositoryWorktree().apply {
						this.checkoutId = checkoutId
						this.branch = branch
						path = dir.toString()
						main = true
						baseBranch = null
						createdAt = now
					})
				}
			} catch (ex: Exception) {
				val reason = cloneFailure(ex, props.cloneTimeout).take(500)
				if (ex is GitException) {
					log.warn("Checkout {}: clone failed: {}", checkoutId, ex.message)
				} else {
					log.error("Checkout {}: clone failed unexpectedly", checkoutId, ex)
				}
				runCatching { deleteFolders(dir, trees) }
					.onFailure { log.warn("Checkout {}: couldn't delete the failed clone's folders", checkoutId, it) }
				checkouts.findById(checkoutId).ifPresent {
					it.state = CheckoutState.FAILED
					it.error = reason
					checkouts.saveAndFlush(it)
				}
			}
		}
	}

	/** On startup, fails the clones a restart interrupted; a Retry cleans up their folders. */
	@EventListener(ApplicationReadyEvent::class)
	fun failInterruptedClones() {
		val count = checkouts.updateState(CheckoutState.CLONING, CheckoutState.FAILED, "The clone was interrupted by a restart.")
		if (count > 0) log.info("Marked {} interrupted clone(s) failed", count)
	}

	/** The checkout with its worktrees, loaded only when it's ready. */
	fun view(checkout: RepositoryCheckout): CheckoutView {
		val list = if (checkout.state == CheckoutState.READY) {
			worktrees.findByCheckoutIdOrderByMainDescCreatedAtAscIdAsc(checkout.id!!).map { WorktreeView(it, false, null) }
		} else {
			emptyList()
		}
		return CheckoutView(checkout, list)
	}

	/** Why a clone failed, in plain words for the project page. */
	internal fun cloneFailure(ex: Exception, timeout: Duration): String = when (ex) {
		is GitTimeoutException -> "The clone took longer than ${describe(timeout)}."
		is GitCommandException -> when (ex.failure) {
			GitFailure.NOT_FOUND -> "GitHub couldn't find the repository, or the git token can't see it."
			GitFailure.AUTH ->
				"GitHub rejected the git token. Check that API_GIT_TOKEN has Contents: read and write on this repository."
			GitFailure.UNREACHABLE -> "GitHub couldn't be reached."
			else -> "The clone failed (git exit ${ex.exitCode})."
		}
		is GitNotConfiguredException -> ex.message!!
		else -> "The clone failed unexpectedly."
	}

	private fun deleteFolders(vararg folders: Path) {
		folders.forEach { if (Files.exists(it)) FileSystemUtils.deleteRecursively(it) }
	}
}
