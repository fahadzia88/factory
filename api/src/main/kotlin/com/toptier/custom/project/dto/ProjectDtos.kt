package com.toptier.custom.project.dto

import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.service.ProjectInput
import com.toptier.custom.project.service.ProjectSummary
import org.springframework.data.domain.Page
import java.time.Instant

/** Body of `POST /api/projects` and `PUT /api/projects/{id}`; missing fields are reported as field errors. */
data class ProjectRequest(
	val name: String? = null,
	val repositoryUrl: String? = null,
	val description: String? = null,
	/** `planning`, `active`, `maintenance` or `retired`; optional on create, where it defaults to planning. */
	val status: String? = null,
) {
	fun toInput() = ProjectInput(name, repositoryUrl, description, status)
}

/** A project as the API returns it. */
data class ProjectResponse(
	val id: Long,
	val name: String,
	val repositoryUrl: String,
	val description: String?,
	val status: String,
	val archived: Boolean,
	/** False when GitHub couldn't be asked when the project was last saved. */
	val repositoryVerified: Boolean,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	companion object {
		fun from(project: Project) = ProjectResponse(
			id = requireNotNull(project.id) { "Project has not been saved" },
			name = project.name,
			repositoryUrl = project.repositoryUrl,
			description = project.description,
			status = project.status.json,
			archived = project.archived,
			repositoryVerified = project.repositoryVerified,
			createdAt = project.createdAt,
			updatedAt = project.updatedAt,
		)
	}
}

/** One page of `GET /api/projects`; [page] is 1-based. A page past the end has empty [items]. */
data class ProjectPageResponse(
	val items: List<ProjectResponse>,
	val page: Int,
	val size: Int,
	val totalItems: Long,
	val totalPages: Int,
) {
	companion object {
		fun from(page: Page<Project>) = ProjectPageResponse(
			items = page.content.map(ProjectResponse::from),
			page = page.number + 1,
			size = page.size,
			totalItems = page.totalElements,
			totalPages = page.totalPages,
		)
	}
}

/** `GET /api/projects/summary`: non-archived counts keyed by status (all four, lifecycle order) and up to 5 recent. */
data class ProjectSummaryResponse(val counts: Map<String, Long>, val recent: List<ProjectResponse>) {
	companion object {
		fun from(summary: ProjectSummary) = ProjectSummaryResponse(
			counts = summary.counts.entries.associateTo(linkedMapOf()) { (status, count) -> status.json to count },
			recent = summary.recent.map(ProjectResponse::from),
		)
	}
}
