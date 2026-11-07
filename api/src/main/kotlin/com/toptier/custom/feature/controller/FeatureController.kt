package com.toptier.custom.feature.controller

import com.toptier.custom.feature.dto.FeatureResponse
import com.toptier.custom.feature.dto.FeatureSummaryResponse
import com.toptier.custom.feature.service.FeatureService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** A project's features. There is no rename, archive or delete. */
@RestController
@RequestMapping("/api/projects/{id}/features")
class FeatureController(private val featureService: FeatureService) {

	/** The project's features, newest number first. */
	@GetMapping
	fun list(@PathVariable id: Long): List<FeatureSummaryResponse> =
		featureService.list(id).map(FeatureSummaryResponse::from)

	/** One feature with its worktree, workflow and steps. */
	@GetMapping("/{featureId}")
	fun get(@PathVariable id: Long, @PathVariable featureId: Long): FeatureResponse =
		FeatureResponse.from(featureService.get(id, featureId))
}
