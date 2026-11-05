package com.toptier.custom.project.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query

/** Stores [Project]s; the existence checks back the uniqueness rules, the rest the list and Dashboard. */
interface ProjectRepository : JpaRepository<Project, Long>, JpaSpecificationExecutor<Project> {

	/** True when any project, archived included, has this lowercased name. */
	fun existsByNameKey(nameKey: String): Boolean

	/** As [existsByNameKey], ignoring the project being updated. */
	fun existsByNameKeyAndIdNot(nameKey: String, id: Long): Boolean

	/** True when any project, archived included, has exactly this repository URL. */
	fun existsByRepositoryUrl(repositoryUrl: String): Boolean

	/** As [existsByRepositoryUrl], ignoring the project being updated. */
	fun existsByRepositoryUrlAndIdNot(repositoryUrl: String, id: Long): Boolean

	/** Non-archived projects per status; statuses with none are absent. */
	@Query("select p.status as status, count(p) as total from Project p where p.archived = false group by p.status")
	fun countByArchivedFalseGroupByStatus(): List<StatusCount>

	/** The five most recently updated non-archived projects, newest first. */
	fun findTop5ByArchivedFalseOrderByUpdatedAtDescIdDesc(): List<Project>
}

/** One row of [ProjectRepository.countByArchivedFalseGroupByStatus]. */
interface StatusCount {
	val status: ProjectStatus
	val total: Long
}
