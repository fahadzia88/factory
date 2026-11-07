package com.toptier.custom.feature.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/** Stores [FeatureStep]s. */
interface FeatureStepRepository : JpaRepository<FeatureStep, Long> {

	/** A feature's steps in the order they were added. */
	fun findByFeatureIdOrderByPositionAsc(featureId: Long): List<FeatureStep>

	/** The step with [id] when it belongs to [featureId], else null. */
	fun findByIdAndFeatureId(id: Long, featureId: Long): FeatureStep?

	/** The feature's highest step position, or null when it has no steps. */
	@Query("select max(s.position) from FeatureStep s where s.featureId = :featureId")
	fun maxPosition(featureId: Long): Int?
}
