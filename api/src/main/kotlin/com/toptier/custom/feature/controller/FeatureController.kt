package com.toptier.custom.feature.controller

import com.toptier.custom.feature.dto.AddStepRequest
import com.toptier.custom.feature.dto.CreateFeatureRequest
import com.toptier.custom.feature.dto.FeatureResponse
import com.toptier.custom.feature.dto.FeatureStepResponse
import com.toptier.custom.feature.dto.FeatureSummaryResponse
import com.toptier.custom.feature.dto.SetStatusRequest
import com.toptier.custom.feature.dto.UpdateStepRequest
import com.toptier.custom.feature.service.FeatureCreator
import com.toptier.custom.feature.service.FeatureProgress
import com.toptier.custom.feature.service.FeatureService
import com.toptier.custom.feature.service.StepChanges
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** A project's features and their progress. There is no rename, archive or delete of features or steps. */
@RestController
@RequestMapping("/api/projects/{id}/features")
class FeatureController(
	private val featureService: FeatureService,
	private val featureCreator: FeatureCreator,
	private val featureProgress: FeatureProgress,
) {

	/** The project's features, newest number first. */
	@GetMapping
	fun list(@PathVariable id: Long): List<FeatureSummaryResponse> =
		featureService.list(id).map(FeatureSummaryResponse::from)

	/** One feature with its worktree, workflow and steps. */
	@GetMapping("/{featureId}")
	fun get(@PathVariable id: Long, @PathVariable featureId: Long): FeatureResponse =
		FeatureResponse.from(featureService.get(id, featureId))

	/** Starts a feature: worktree, `requirements.md` from the template, commit, push, record. */
	@PostMapping
	fun create(@PathVariable id: Long, @RequestBody request: CreateFeatureRequest): ResponseEntity<FeatureResponse> =
		ResponseEntity.status(HttpStatus.CREATED).body(FeatureResponse.from(featureCreator.create(id, request.name)))

	/** Sets the feature's status to one of its workflow's states. */
	@PutMapping("/{featureId}/status")
	fun setStatus(@PathVariable id: Long, @PathVariable featureId: Long, @RequestBody request: SetStatusRequest): FeatureResponse =
		FeatureResponse.from(featureProgress.setStatus(id, featureId, request.status))

	/** Adds a step of one of the workflow's step types. */
	@PostMapping("/{featureId}/steps")
	fun addStep(
		@PathVariable id: Long,
		@PathVariable featureId: Long,
		@RequestBody request: AddStepRequest,
	): ResponseEntity<FeatureStepResponse> {
		val (step, workflow) = featureProgress.addStep(id, featureId, request.type, request.title, request.documentPath)
		return ResponseEntity.status(HttpStatus.CREATED).body(FeatureStepResponse.from(step, workflow))
	}

	/** Changes a step's status, title or document path. */
	@PatchMapping("/{featureId}/steps/{stepId}")
	fun updateStep(
		@PathVariable id: Long,
		@PathVariable featureId: Long,
		@PathVariable stepId: Long,
		@RequestBody request: UpdateStepRequest,
	): FeatureStepResponse {
		val changes = StepChanges(request.status, request.title, request.documentPath)
		val (step, workflow) = featureProgress.updateStep(id, featureId, stepId, changes)
		return FeatureStepResponse.from(step, workflow)
	}
}
