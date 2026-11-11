package com.toptier.custom.feature.controller

import com.toptier.custom.feature.dto.TaskResponse
import com.toptier.custom.feature.service.FeatureTasks
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** A feature's stack of task branches and their pull requests. */
@RestController
@RequestMapping("/api/projects/{id}/features/{featureId}/tasks")
class FeatureTaskController(private val featureTasks: FeatureTasks) {

	/** Every task, row 0 (the docs branch) first. */
	@GetMapping
	fun list(@PathVariable id: Long, @PathVariable featureId: Long): List<TaskResponse> =
		featureTasks.list(id, featureId).map(TaskResponse::from)

	/** One task; a GitHub failure on its pull request answers with that error. */
	@GetMapping("/{number}")
	fun get(@PathVariable id: Long, @PathVariable featureId: Long, @PathVariable number: Int): TaskResponse =
		TaskResponse.from(featureTasks.get(id, featureId, number))
}
