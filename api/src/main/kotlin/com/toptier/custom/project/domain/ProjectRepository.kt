package com.toptier.custom.project.domain

import org.springframework.data.jpa.repository.JpaRepository

/** Stores [Project]s; the existence checks back the uniqueness rules. */
interface ProjectRepository : JpaRepository<Project, Long> {

	/** True when any project, archived included, has this lowercased name. */
	fun existsByNameKey(nameKey: String): Boolean

	/** As [existsByNameKey], ignoring the project being updated. */
	fun existsByNameKeyAndIdNot(nameKey: String, id: Long): Boolean

	/** True when any project, archived included, has exactly this repository URL. */
	fun existsByRepositoryUrl(repositoryUrl: String): Boolean

	/** As [existsByRepositoryUrl], ignoring the project being updated. */
	fun existsByRepositoryUrlAndIdNot(repositoryUrl: String, id: Long): Boolean
}
