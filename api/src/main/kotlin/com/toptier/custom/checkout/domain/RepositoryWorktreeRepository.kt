package com.toptier.custom.checkout.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/** Stores [RepositoryWorktree]s. */
interface RepositoryWorktreeRepository : JpaRepository<RepositoryWorktree, Long> {

	/** A checkout's worktrees: the main one first, then oldest first. */
	fun findByCheckoutIdOrderByMainDescCreatedAtAscIdAsc(checkoutId: Long): List<RepositoryWorktree>

	/** The worktree with [id] when it belongs to [checkoutId], else null. */
	fun findByIdAndCheckoutId(id: Long, checkoutId: Long): RepositoryWorktree?

	/** True when the checkout already has a worktree for [branch]. */
	fun existsByCheckoutIdAndBranch(checkoutId: Long, branch: String): Boolean

	/** Removes every worktree row of the checkout. */
	@Transactional
	@Modifying
	@Query("delete from RepositoryWorktree w where w.checkoutId = :checkoutId")
	fun deleteByCheckoutId(checkoutId: Long)
}
