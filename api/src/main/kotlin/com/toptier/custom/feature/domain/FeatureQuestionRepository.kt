package com.toptier.custom.feature.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/** Stores [FeatureQuestion] sets. */
interface FeatureQuestionRepository : JpaRepository<FeatureQuestion, Long> {

	/** The feature's sets in [status]. */
	fun findByFeatureIdAndStatus(featureId: Long, status: QuestionStatus): List<FeatureQuestion>

	/** The feature's newest set in [status], or null. */
	fun findFirstByFeatureIdAndStatusOrderByIdDesc(featureId: Long, status: QuestionStatus): FeatureQuestion?

	/** The feature's sets, oldest first. */
	fun findByFeatureIdOrderByIdAsc(featureId: Long): List<FeatureQuestion>

	/** The set with [id] when it belongs to [featureId], else null. */
	fun findByIdAndFeatureId(id: Long, featureId: Long): FeatureQuestion?

	/** Marks the feature's pending sets superseded; a newer set replaces them. */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		"update FeatureQuestion q set q.status = com.toptier.custom.feature.domain.QuestionStatus.SUPERSEDED " +
			"where q.featureId = :featureId and q.status = com.toptier.custom.feature.domain.QuestionStatus.PENDING",
	)
	fun supersedePending(featureId: Long): Int
}
