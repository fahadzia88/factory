package com.toptier.custom.feature.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/** A step of a [Feature], of one of its workflow's step types, numbered in the order the agent added it. */
@Entity
@Table(
	name = "feature_step",
	uniqueConstraints = [UniqueConstraint(name = "uk_feature_step_position", columnNames = ["feature_id", "position"])],
)
class FeatureStep {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null

	/** A plain id of the owning [Feature]. */
	@Column(name = "feature_id", nullable = false)
	var featureId: Long = 0

	@Column(nullable = false)
	var position: Int = 0

	/** The workflow step type's key. */
	@Column(name = "type_key", nullable = false, length = 40)
	var typeKey: String = ""

	@Column(nullable = false, length = 200)
	var title: String = ""

	/** The step's document, a path inside the feature's folder; null when it has none. */
	@Column(name = "document_path", length = 300)
	var documentPath: String? = null

	/** One of the step type's states. */
	@Column(nullable = false, length = 40)
	var status: String = ""

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.EPOCH

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = Instant.EPOCH
}
