package com.toptier.custom.project.controller

import com.toptier.custom.project.dto.ProjectRequest
import com.toptier.custom.project.dto.ProjectResponse
import com.toptier.custom.project.service.ProjectService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/** Software projects. There is deliberately no DELETE: projects are archived, never removed. */
@RestController
@RequestMapping("/api/projects")
class ProjectController(private val projectService: ProjectService) {

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

	/** Hides the project from the default list and makes it read-only. Idempotent. */
	@PostMapping("/{id}/archive")
	fun archive(@PathVariable id: Long): ProjectResponse = ProjectResponse.from(projectService.archive(id))

	/** Makes an archived project listed and editable again. Idempotent. */
	@PostMapping("/{id}/restore")
	fun restore(@PathVariable id: Long): ProjectResponse = ProjectResponse.from(projectService.restore(id))
}
