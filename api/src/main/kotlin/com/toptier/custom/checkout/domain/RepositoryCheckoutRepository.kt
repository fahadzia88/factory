package com.toptier.custom.checkout.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/** Stores [RepositoryCheckout]s. */
interface RepositoryCheckoutRepository : JpaRepository<RepositoryCheckout, Long> {

	/** The project's checkout, or null when it has none. */
	fun findByProjectId(projectId: Long): RepositoryCheckout?

	/** Every checkout in [state]. */
	fun findAllByState(state: CheckoutState): List<RepositoryCheckout>

	/** Moves every checkout in [from] to [to] with [error]; returns how many changed. Backs the startup reset. */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("update RepositoryCheckout c set c.state = :to, c.error = :error where c.state = :from")
	fun updateState(from: CheckoutState, to: CheckoutState, error: String): Int
}
