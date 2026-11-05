package com.toptier.custom.project.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** A software project the factory builds: a named codebase with a GitHub repository. */
@Entity
@Table(name = "project")
class Project {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null

	@Column(nullable = false, length = 100)
	var name: String = ""

	/** [name] lowercased, so names are unique ignoring case. */
	@Column(name = "name_key", nullable = false, unique = true, length = 100)
	var nameKey: String = ""

	/** Unique as exact text: differently written URLs of one repository are different values. */
	@Column(name = "repository_url", nullable = false, unique = true, length = 300)
	var repositoryUrl: String = ""

	@Column(length = 2000)
	var description: String? = null

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	var status: ProjectStatus = ProjectStatus.PLANNING

	/** [status]'s ordinal, so sorting by status follows the lifecycle rather than the alphabet. */
	@Column(name = "status_order", nullable = false)
	var statusOrder: Int = ProjectStatus.PLANNING.ordinal

	/** Archived projects are hidden from the default list and are read-only until restored. */
	@Column(nullable = false)
	var archived: Boolean = false

	/** False when GitHub couldn't be asked on the last save; the next save checks again. */
	@Column(name = "repository_verified", nullable = false)
	var repositoryVerified: Boolean = false

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.EPOCH

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = Instant.EPOCH
}
