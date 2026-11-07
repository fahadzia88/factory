package com.toptier.custom.feature.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/** A feature of a project, numbered per project, worked on in the worktree of [branch]. */
@Entity
@Table(
	name = "feature",
	uniqueConstraints = [UniqueConstraint(name = "uk_feature_project_number", columnNames = ["project_id", "number"])],
)
class Feature {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null

	/** A plain id of the owning project. */
	@Column(name = "project_id", nullable = false)
	var projectId: Long = 0

	@Column(nullable = false)
	var number: Int = 0

	@Column(nullable = false, length = 100)
	var name: String = ""

	@Column(nullable = false, length = 40)
	var slug: String = ""

	/** One of the [workflow]'s feature states. */
	@Column(nullable = false, length = 40)
	var status: String = ""

	/** The workflow the feature was created under, as JSON; statuses are checked against it. */
	@Column(nullable = false, columnDefinition = "TEXT")
	var workflow: String = ""

	@Column(nullable = false, length = 255)
	var branch: String = ""

	/** The feature's folder in the repository, e.g. `feature/004-features`. */
	@Column(nullable = false, length = 255)
	var folder: String = ""

	/** The agent's conversation for the feature chat; null until the first turn. */
	@Column(name = "agent_session_id", length = 64)
	var agentSessionId: String? = null

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.EPOCH

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = Instant.EPOCH
}
