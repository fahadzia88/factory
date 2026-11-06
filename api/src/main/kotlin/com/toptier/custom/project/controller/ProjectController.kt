package com.toptier.custom.project.controller

import com.toptier.custom.project.dto.ProjectPageResponse
import com.toptier.custom.project.dto.ProjectRequest
import com.toptier.custom.project.dto.ProjectResponse
import com.toptier.custom.project.dto.ProjectSummaryResponse
import com.toptier.custom.project.service.ProjectListQuery
import com.toptier.custom.project.service.ProjectService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/** Software projects. There is deliberately no DELETE: projects are archived, never removed. */
@RestController
@RequestMapping("/api/projects")
class ProjectController(private val projectService: ProjectService) {

	/** A page of projects: name search, status filter, archived on request, sorted (default: last updated first). */
	@GetMapping
	fun list(
		@RequestParam(required = false) q: String?,
		@RequestParam(required = false) status: String?,
		@RequestParam(required = false) includeArchived: Boolean?,
		@RequestParam(required = false) sort: String?,
		@RequestParam(required = false) direction: String?,
		@RequestParam(required = false) page: Int?,
		@RequestParam(required = false) size: Int?,
	): ProjectPageResponse =
		ProjectPageResponse.from(projectService.list(ProjectListQuery.of(q, status, includeArchived, sort, direction, page, size)))

	/** Non-archived counts per status and the five most recently updated projects, for the Dashboard. */
	@GetMapping("/summary")
	fun summary(): ProjectSummaryResponse = ProjectSummaryResponse.from(projectService.summary())

	/** One project, archived or not. */
	@GetMapping("/{id}")
	fun get(@PathVariable id: Long): ProjectResponse = ProjectResponse.from(projectService.get(id))

	/** Creates a project after checking its repository with GitHub; answers 201 with its location. */
	@PostMapping
	fun create(@RequestBody request: ProjectRequest): ResponseEntity<ProjectResponse> {
		val project = ProjectResponse.from(projectService.create(request.toInput()))
		return ResponseEntity.created(URI("/api/projects/${project.id}")).body(project)
	}

	/** Replaces a non-archived project's fields; status is required. */
	@PutMapping("/{id}")
	fun update(@PathVariable id: Long, @RequestBody request: ProjectRequest): ProjectResponse =
		ProjectResponse.from(projectService.update(id, request.toInput()))

	/** Hides the project from the default list, makes it read-only and deletes its repository checkout. Idempotent. */
	@PostMapping("/{id}/archive")
	fun archive(@PathVariable id: Long): ProjectResponse = ProjectResponse.from(projectService.archive(id))

	/** Makes an archived project listed and editable again. Idempotent. */
	@PostMapping("/{id}/restore")
	fun restore(@PathVariable id: Long): ProjectResponse = ProjectResponse.from(projectService.restore(id))

	/** Re-checks an unverified project's repository with GitHub; 422 if it's missing, 503 if GitHub can't be asked. */
	@PostMapping("/{id}/verify")
	fun verify(@PathVariable id: Long): ProjectResponse = ProjectResponse.from(projectService.verify(id))
}
