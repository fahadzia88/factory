package com.toptier.custom.feature.controller

import com.toptier.custom.feature.dto.WorkflowResponse
import com.toptier.custom.feature.service.ProjectWorkflow
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** Read-only view of a project's feature workflow, for showing it in the app. */
@RestController
class ProjectWorkflowController(private val projectWorkflow: ProjectWorkflow) {

	/** The project's workflow from its checkout; `409 checkout_not_ready` or `409 workflow_invalid` otherwise. */
	@GetMapping("/api/projects/{id}/workflow")
	fun get(@PathVariable id: Long): WorkflowResponse = WorkflowResponse.from(projectWorkflow.get(id))
}
