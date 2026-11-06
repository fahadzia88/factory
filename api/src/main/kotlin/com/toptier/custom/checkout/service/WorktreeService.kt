package com.toptier.custom.checkout.service

import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckout
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktree
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.checkout.exception.BaseNotFoundException
import com.toptier.custom.checkout.exception.BranchCheckedOutException
import com.toptier.custom.checkout.exception.CheckoutException
import com.toptier.custom.checkout.exception.CheckoutNotFoundException
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.checkout.exception.GitCommandException
import com.toptier.custom.checkout.exception.GitException
import com.toptier.custom.checkout.exception.GitFailure
import com.toptier.custom.checkout.exception.GitRemoteException
import com.toptier.custom.checkout.exception.InvalidBranchException
import com.toptier.custom.checkout.exception.MainWorktreeException
import com.toptier.custom.checkout.exception.PushDefaultBranchException
import com.toptier.custom.checkout.exception.PushRejectedException
import com.toptier.custom.checkout.exception.WorktreeMissingException
import com.toptier.custom.checkout.exception.WorktreeNotFoundException
import com.toptier.custom.checkout.exception.WorktreeUnsavedException
import com.toptier.custom.project.service.ProjectService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.util.FileSystemUtils
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/** A located checkout and, when one was asked for, one of its worktrees. */
data class WorktreeLocation(val checkout: RepositoryCheckout, val worktree: RepositoryWorktree?)

/** A pushed worktree: its [branch] and the short SHA of its [head]. */
data class PushResult(val branch: String, val head: String)

/**
 * Adds, removes and pushes a checkout's worktrees, one folder per branch under `<root>/<projectId>.worktrees`.
 * Like [CheckoutService], not `@Transactional`: no transaction stays open across a git call.
 */
@Service
class WorktreeService(
	private val projects: ProjectService,
	private val checkouts: RepositoryCheckoutRepository,
	private val worktrees: RepositoryWorktreeRepository,
	private val checkoutService: CheckoutService,
	private val inspector: WorktreeInspector,
	private val runner: GitRunner,
	private val paths: CheckoutPaths,
	private val locks: CheckoutLocks,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(WorktreeService::class.java)

	/**
	 * The project's checkout and, with [worktreeId], its worktree. Throws `ProjectNotFoundException`,
	 * [CheckoutNotFoundException] or [WorktreeNotFoundException] (also for another checkout's worktree).
	 */
	fun locate(projectId: Long, worktreeId: Long? = null): WorktreeLocation {
		projects.get(projectId)
		val checkout = checkouts.findByProjectId(projectId) ?: throw CheckoutNotFoundException()
		val worktree = worktreeId?.let {
			worktrees.findByIdAndCheckoutId(it, checkout.id!!) ?: throw WorktreeNotFoundException()
		}
		return WorktreeLocation(checkout, worktree)
	}

	/**
	 * Fetches, then adds a worktree for [branch]: the local branch if there is one, else one tracking
	 * `origin/<branch>`, else a new branch from [base] (default: the checkout's default branch) with
	 * no upstream. Throws [InvalidBranchException], [BaseNotFoundException], [BranchCheckedOutException],
	 * [CheckoutNotReadyException], and the fetch's git exceptions.
	 */
	fun add(projectId: Long, branch: String?, base: String?): WorktreeView {
		val checkout = locate(projectId).checkout
		if (checkout.state != CheckoutState.READY) throw CheckoutNotReadyException()
		val name = branch?.trim()
		if (name.isNullOrEmpty() || !runner.isValidBranchName(name)) throw InvalidBranchException(name)
		val baseName = base?.trim()?.takeIf { it.isNotEmpty() }
		if (baseName != null && !runner.isValidBranchName(baseName)) throw BaseNotFoundException(baseName)
		val dir = paths.worktreeDir(projectId, name)
		refuseTaken(checkout, name, dir)
		runner.requireToken()
		val checkoutId = checkout.id!!
		return locks.withLock(checkoutId) {
			refuseTaken(checkout, name, dir)
			checkoutService.fetchUnderLock(checkout)
			val clone = Path.of(checkout.path)
			runner.runOrThrow(clone, listOf("worktree", "prune"))
			Files.createDirectories(paths.worktreesDir(projectId))
			val baseBranch = when {
				runner.refExists(clone, "refs/heads/$name") -> {
					runner.runOrThrow(clone, listOf("worktree", "add", dir.toString(), name))
					null
				}
				runner.refExists(clone, "refs/remotes/origin/$name") -> {
					runner.runOrThrow(clone, listOf("worktree", "add", "--track", "-b", name, dir.toString(), "origin/$name"))
					null
				}
				else -> {
					val from = baseName ?: checkout.defaultBranch!!
					val ref = when {
						runner.refExists(clone, "refs/remotes/origin/$from") -> "origin/$from"
						runner.refExists(clone, "refs/heads/$from") -> from
						else -> throw BaseNotFoundException(from)
					}
					runner.runOrThrow(clone, listOf("worktree", "add", "--no-track", "-b", name, dir.toString(), ref))
					from
				}
			}
			val row = RepositoryWorktree().apply {
				this.checkoutId = checkoutId
				this.branch = name
				path = dir.toString()
				main = false
				this.baseBranch = baseBranch
				createdAt = clock.instant()
			}
			val saved = try {
				worktrees.saveAndFlush(row)
			} catch (_: DataIntegrityViolationException) {
				runner.run(clone, listOf("worktree", "remove", "--force", dir.toString()))
				throw BranchCheckedOutException.of(name)
			}
			WorktreeView(saved, inspector.missing(dir), inspector.head(dir))
		}
	}

	/**
	 * Removes a worktree's folder and row, refusing with [WorktreeUnsavedException] when it has work
	 * that isn't on GitHub, unless [force]. The local branch is deleted only when origin has it, so
	 * forced-away commits stay reachable. A missing folder is pruned. Throws [MainWorktreeException]
	 * for the main worktree.
	 */
	fun remove(projectId: Long, worktreeId: Long, force: Boolean) {
		val (checkout, found) = locate(projectId, worktreeId)
		val worktree = found!!
		if (worktree.main) throw MainWorktreeException()
		locks.withLock(checkout.id!!) {
			val clone = Path.of(checkout.path)
			val dir = Path.of(worktree.path)
			if (inspector.missing(dir)) {
				runner.runOrThrow(clone, listOf("worktree", "prune"))
				worktrees.delete(worktree)
				return@withLock
			}
			if (!force) {
				val unsaved = inspector.unsaved(worktree)
				if (!unsaved.isEmpty()) throw WorktreeUnsavedException(unsaved)
			}
			try {
				runner.runOrThrow(clone, listOf("worktree", "remove", "--force", dir.toString()))
			} catch (ex: GitCommandException) {
				if (!ex.stderr.contains("is not a working tree")) throw ex
				log.info("Worktree {}: folder isn't registered with git, deleting it", worktree.id)
				FileSystemUtils.deleteRecursively(dir)
				runner.runOrThrow(clone, listOf("worktree", "prune"))
			}
			if (runner.refExists(clone, "refs/remotes/origin/${worktree.branch}")) {
				runner.runOrThrow(clone, listOf("branch", "-D", worktree.branch))
			}
			worktrees.delete(worktree)
		}
	}

	/**
	 * Pushes the worktree's branch to `origin` with its upstream set, then fetches. With [force] it
	 * pushes `--force-with-lease`, compared against `origin/<branch>` as of the last fetch, so there's
	 * deliberately no fetch first. Throws [PushDefaultBranchException] (before any git runs),
	 * [WorktreeMissingException], [PushRejectedException], and [GitRemoteException] for other remote failures.
	 */
	fun push(projectId: Long, worktreeId: Long, force: Boolean): PushResult {
		val (checkout, found) = locate(projectId, worktreeId)
		val worktree = found!!
		val branch = worktree.branch
		if (branch == checkout.defaultBranch) throw PushDefaultBranchException(branch)
		val dir = Path.of(worktree.path)
		if (inspector.missing(dir)) throw WorktreeMissingException(branch)
		runner.requireToken()
		return locks.withLock(checkout.id!!) {
			val args = buildList {
				add("push")
				if (force) add("--force-with-lease=$branch")
				addAll(listOf("-u", "origin", "$branch:$branch"))
			}
			try {
				runner.runOrThrow(dir, args, network = true)
			} catch (ex: GitCommandException) {
				throw when (ex.failure) {
					GitFailure.REJECTED -> PushRejectedException(lease = false)
					GitFailure.STALE_LEASE -> PushRejectedException(lease = true)
					else -> GitRemoteException(ex.failure)
				}
			}
			try {
				checkoutService.fetchUnderLock(checkout)
			} catch (ex: GitException) {
				log.warn("Checkout {}: fetch after pushing {} failed: {}", checkout.id, branch, ex.message)
			} catch (ex: CheckoutException) {
				log.warn("Checkout {}: fetch after pushing {} failed: {}", checkout.id, branch, ex.message)
			}
			PushResult(branch, runner.runOrThrow(dir, listOf("rev-parse", "--short", "HEAD")).trim())
		}
	}

	/** Throws [BranchCheckedOutException] when [name] has a worktree or another worktree has its folder. */
	private fun refuseTaken(checkout: RepositoryCheckout, name: String, dir: Path) {
		if (name == checkout.defaultBranch || worktrees.existsByCheckoutIdAndBranch(checkout.id!!, name)) {
			throw BranchCheckedOutException.of(name)
		}
		if (Files.exists(dir)) throw BranchCheckedOutException.folder(name, dir.fileName.toString())
	}
}
