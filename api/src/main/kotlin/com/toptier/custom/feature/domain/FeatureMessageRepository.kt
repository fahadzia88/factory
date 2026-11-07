package com.toptier.custom.feature.domain

import org.springframework.data.jpa.repository.JpaRepository

/** Stores [FeatureMessage]s. */
interface FeatureMessageRepository : JpaRepository<FeatureMessage, Long> {

	/** A feature's chat, oldest first. */
	fun findByFeatureIdOrderByCreatedAtAscIdAsc(featureId: Long): List<FeatureMessage>
}
