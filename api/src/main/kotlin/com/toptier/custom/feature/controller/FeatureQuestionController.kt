package com.toptier.custom.feature.controller

import com.toptier.custom.feature.dto.QuestionsCreated
import com.toptier.custom.feature.dto.QuestionsRequest
import com.toptier.custom.feature.service.FeatureQuestions
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The questions the agent asks in a feature's chat. */
@RestController
@RequestMapping("/api/projects/{id}/features/{featureId}/questions")
class FeatureQuestionController(private val featureQuestions: FeatureQuestions) {

	/** Stores a set of questions as the feature's pending set. */
	@PostMapping
	fun create(
		@PathVariable id: Long,
		@PathVariable featureId: Long,
		@RequestBody(required = false) request: QuestionsRequest?,
	): ResponseEntity<QuestionsCreated> =
		ResponseEntity.status(HttpStatus.CREATED).body(QuestionsCreated(featureQuestions.create(id, featureId, request).id!!))
}
