package com.toptier.custom.checkout.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/** A worktree of a [RepositoryCheckout]: the main one (the clone itself) or one added per branch. */
@Entity
@Table(
	name = "repository_worktree",
	uniqueConstraints = [UniqueConstraint(name = "uk_worktree_checkout_branch", columnNames = ["checkout_id", "branch"])],
)
class RepositoryWorktree {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null

	/** A plain id of the owning [RepositoryCheckout]. */
	@Column(name = "checkout_id", nullable = false)
	var checkoutId: Long = 0

	@Column(nullable = false, length = 255)
	var branch: String = ""

	/** Absolute path of the worktree. */
	@Column(nullable = false, length = 600)
	var path: String = ""

	/** True for the clone's own working tree, on the default branch. */
	@Column(name = "main", nullable = false)
	var main: Boolean = false

	/** The branch a new feature branch was created from; null for tracked branches and the main worktree. */
	@Column(name = "base_branch", length = 255)
	var baseBranch: String? = null

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.EPOCH
}
