package com.toptier.custom.feature.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/** Stores [Feature]s. */
interface FeatureRepository : JpaRepository<Feature, Long> {

	/** A project's features, newest number first. */
	fun findByProjectIdOrderByNumberDesc(projectId: Long): List<Feature>

	/** The feature with [id] when it belongs to [projectId], else null. */
	fun findByIdAndProjectId(id: Long, projectId: Long): Feature?

	/** The project's highest feature number, or null when it has none. */
	@Query("select max(f.number) from Feature f where f.projectId = :projectId")
	fun maxNumber(projectId: Long): Int?
}
