package com.toptier.custom.feature.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * One branch in a feature's stack: row 0 is the docs branch, the others are its implementation tasks in
 * stack order. [baseBranch] and [baseHead] record what the branch was last built on, to tell when it needs a restack.
 */
@Entity
@Table(
	name = "feature_task",
	uniqueConstraints = [UniqueConstraint(name = "uk_feature_task_number", columnNames = ["feature_id", "number"])],
)
class FeatureTask {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null

	/** A plain id of the owning [Feature]. */
	@Column(name = "feature_id", nullable = false)
	var featureId: Long = 0

	@Column(nullable = false)
	var number: Int = 0

	/** `docs` for row 0, else `api`, `app`, `agent` or `infra`. */
	@Column(nullable = false, length = 10)
	var layer: String = ""

	@Column(nullable = false, length = 60)
	var slug: String = ""

	@Column(nullable = false, length = 200)
	var branch: String = ""

	@Column(name = "base_branch", nullable = false, length = 200)
	var baseBranch: String = ""

	/** The base's head SHA when the branch was last built on it; null until then. */
	@Column(name = "base_head", length = 64)
	var baseHead: String? = null

	/** The task's spec in the feature folder; null for row 0. */
	@Column(name = "spec_path", length = 300)
	var specPath: String? = null

	/** The feature step recording the task; null for row 0. */
	@Column(name = "step_id")
	var stepId: Long? = null

	@Column(name = "pull_number")
	var pullNumber: Int? = null

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.EPOCH

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = Instant.EPOCH
}
