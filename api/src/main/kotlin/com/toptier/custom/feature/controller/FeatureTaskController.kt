package com.toptier.custom.feature.controller

import com.toptier.custom.feature.dto.OpenPullRequest
import com.toptier.custom.feature.dto.StartTaskRequest
import com.toptier.custom.feature.dto.TaskResponse
import com.toptier.custom.feature.service.FeatureTasks
import com.toptier.custom.feature.service.TaskPulls
import com.toptier.custom.feature.service.TaskStarter
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** A feature's stack of task branches and their pull requests. */
@RestController
@RequestMapping("/api/projects/{id}/features/{featureId}/tasks")
class FeatureTaskController(
	private val featureTasks: FeatureTasks,
	private val taskStarter: TaskStarter,
	private val taskPulls: TaskPulls,
) {

	/** Every task, row 0 (the docs branch) first. */
	@GetMapping
	fun list(@PathVariable id: Long, @PathVariable featureId: Long): List<TaskResponse> =
		featureTasks.list(id, featureId).map(TaskResponse::from)

	/** One task; a GitHub failure on its pull request answers with that error. */
	@GetMapping("/{number}")
	fun get(@PathVariable id: Long, @PathVariable featureId: Long, @PathVariable number: Int): TaskResponse =
		TaskResponse.from(featureTasks.get(id, featureId, number))

	/** Starts the next task: its worktree, row and step. */
	@PostMapping
	fun start(@PathVariable id: Long, @PathVariable featureId: Long, @RequestBody request: StartTaskRequest): ResponseEntity<TaskResponse> =
		ResponseEntity.status(HttpStatus.CREATED).body(TaskResponse.from(taskStarter.start(id, featureId, request.dir)))

	/** Opens task [n]'s pull request; row 0 (the docs branch) takes no body. */
	@PostMapping("/{n}/pull")
	fun openPull(
		@PathVariable id: Long,
		@PathVariable featureId: Long,
		@PathVariable n: Int,
		@RequestBody(required = false) request: OpenPullRequest?,
	): ResponseEntity<TaskResponse> =
		ResponseEntity.status(HttpStatus.CREATED).body(TaskResponse.from(taskPulls.open(id, featureId, n, request?.task, request?.acs)))
}
