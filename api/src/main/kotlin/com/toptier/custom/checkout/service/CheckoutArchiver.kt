package com.toptier.custom.checkout.service

import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckout
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.project.service.ProjectArchivedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.util.FileSystemUtils
import java.io.IOException

/**
 * Deletes an archived project's checkout, whatever its state and unsaved work: the rows in the
 * archive's transaction, the folders once it commits. The app warns about unsaved work first.
 */
@Component
class CheckoutArchiver(
	private val checkouts: RepositoryCheckoutRepository,
	private val worktrees: RepositoryWorktreeRepository,
	private val paths: CheckoutPaths,
	private val locks: CheckoutLocks,
) {

	private val log = LoggerFactory.getLogger(CheckoutArchiver::class.java)

	/** Runs synchronously in the archive's transaction, so the row deletes commit or roll back with it. */
	@EventListener
	fun onProjectArchived(event: ProjectArchivedEvent) {
		val checkout = checkouts.findByProjectId(event.projectId) ?: return
		worktrees.deleteByCheckoutId(checkout.id!!)
		checkouts.delete(checkout)
		checkouts.flush()
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
				override fun afterCommit() = deleteFolders(checkout)
			})
		} else {
			deleteFolders(checkout)
		}
	}

	/**
	 * Deletes the clone and worktrees folders under the checkout's lock, so a fetch or push in
	 * progress finishes first. A cloning checkout is left alone: its clone holds the lock, finds
	 * its row gone and deletes the folders itself. A failure is logged; the next clone cleans up.
	 */
	private fun deleteFolders(checkout: RepositoryCheckout) {
		if (checkout.state == CheckoutState.CLONING) return
		try {
			locks.withLock(checkout.id!!) {
				FileSystemUtils.deleteRecursively(paths.worktreesDir(checkout.projectId))
				FileSystemUtils.deleteRecursively(paths.checkoutDir(checkout.projectId))
			}
		} catch (ex: IOException) {
			log.warn("Project {}: couldn't delete the archived checkout's folders", checkout.projectId, ex)
		}
	}
}
