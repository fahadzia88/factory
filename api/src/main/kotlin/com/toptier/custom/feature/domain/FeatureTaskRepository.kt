package com.toptier.custom.feature.domain

import org.springframework.data.jpa.repository.JpaRepository

/** Stores [FeatureTask]s. */
interface FeatureTaskRepository : JpaRepository<FeatureTask, Long> {

	/** A feature's tasks, row 0 first. */
	fun findByFeatureIdOrderByNumberAsc(featureId: Long): List<FeatureTask>

	/** The feature's task [number], or null. */
	fun findByFeatureIdAndNumber(featureId: Long, number: Int): FeatureTask?
}
