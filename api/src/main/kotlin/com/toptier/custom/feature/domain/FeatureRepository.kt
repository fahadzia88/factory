package com.toptier.custom.feature.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/** Stores [Feature]s. */
interface FeatureRepository : JpaRepository<Feature, Long> {

	/** A project's features, newest number first. */
	fun findByProjectIdOrderByNumberDesc(projectId: Long): List<Feature>

	/** The feature with [id] when it belongs to [projectId], else null. */
	fun findByIdAndProjectId(id: Long, projectId: Long): Feature?

	/** The project's highest feature number, or null when it has none. */
	@Query("select max(f.number) from Feature f where f.projectId = :projectId")
	fun maxNumber(projectId: Long): Int?

	/** Sets only the chat's session id, so a turn never overwrites a status the agent set meanwhile. */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Feature f set f.agentSessionId = :sessionId where f.id = :id")
	fun updateAgentSessionId(id: Long, sessionId: String?)
}
