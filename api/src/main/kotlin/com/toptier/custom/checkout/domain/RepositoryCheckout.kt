package com.toptier.custom.checkout.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** A project's clone on the shared volume; at most one per project. */
@Entity
@Table(name = "repository_checkout")
class RepositoryCheckout {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null

	/** A plain id, not a relation: the checkout package reads projects only through `ProjectService`. */
	@Column(name = "project_id", nullable = false, unique = true)
	var projectId: Long = 0

	/** Absolute path of the clone (the main worktree). */
	@Column(nullable = false, length = 300)
	var path: String = ""

	/** The remote's default branch; null until cloned. */
	@Column(name = "default_branch", length = 255)
	var defaultBranch: String? = null

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	var state: CheckoutState = CheckoutState.CLONING

	/** Why the clone failed, in plain words; null unless [state] is FAILED. */
	@Column(length = 500)
	var error: String? = null

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.EPOCH

	@Column(name = "last_fetched_at")
	var lastFetchedAt: Instant? = null
}
