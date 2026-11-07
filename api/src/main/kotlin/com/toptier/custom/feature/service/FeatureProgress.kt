package com.toptier.custom.feature.service

import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.domain.FeatureStep
import com.toptier.custom.feature.domain.FeatureStepRepository
import com.toptier.custom.feature.exception.InvalidStatusException
import com.toptier.custom.feature.exception.InvalidStepException
import com.toptier.custom.feature.exception.InvalidStepTypeException
import com.toptier.custom.feature.exception.StepNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** A step's fields to change; null leaves a field as it is, and an empty [documentPath] clears it. */
data class StepChanges(val status: String? = null, val title: String? = null, val documentPath: String? = null)

/**
 * Records a feature's progress as the agent reports it: the feature's status and its steps, each
 * checked against the workflow the feature was created under. Archived projects still accept them.
 */
@Service
class FeatureProgress(
	private val featureService: FeatureService,
	private val features: FeatureRepository,
	private val steps: FeatureStepRepository,
	private val codec: WorkflowCodec,
	private val clock: Clock,
) {

	/** Sets the feature's status to one of its workflow's feature states. */
	@Transactional
	fun setStatus(projectId: Long, featureId: Long, status: String?): FeatureView {
		val feature = featureService.load(projectId, featureId)
		val workflow = codec.read(feature.workflow)
		val value = status?.trim()
		if (value == null || value !in workflow.featureStates) throw InvalidStatusException(status, workflow.featureStates)
		feature.status = value
		touch(feature)
		return featureService.view(feature)
	}

	/** Adds a step of [type] in that type's first state, after the feature's other steps. */
	@Transactional
	fun addStep(projectId: Long, featureId: Long, type: String?, title: String?, documentPath: String?): Pair<FeatureStep, Workflow> {
		val feature = featureService.load(projectId, featureId)
		val workflow = codec.read(feature.workflow)
		val stepType = type?.trim()?.let(workflow::step) ?: throw InvalidStepTypeException(type, workflow.steps.map { it.key })
		val now = clock.instant()
		val step = steps.saveAndFlush(FeatureStep().apply {
			this.featureId = featureId
			position = (steps.maxPosition(featureId) ?: 0) + 1
			typeKey = stepType.key
			this.title = checkTitle(title)
			this.documentPath = documentPath?.let { checkPath(feature, it) }
			status = stepType.states.first()
			createdAt = now
			updatedAt = now
		})
		touch(feature)
		return step to workflow
	}

	/** Changes a step's status (one of its type's states), title or document path. */
	@Transactional
	fun updateStep(projectId: Long, featureId: Long, stepId: Long, changes: StepChanges): Pair<FeatureStep, Workflow> {
		val feature = featureService.load(projectId, featureId)
		val workflow = codec.read(feature.workflow)
		val step = steps.findByIdAndFeatureId(stepId, featureId) ?: throw StepNotFoundException()
		if (changes.status == null && changes.title == null && changes.documentPath == null) {
			throw InvalidStepException("Send a status, title or documentPath to change.")
		}
		changes.status?.let { raw ->
			val states = workflow.step(step.typeKey)?.states.orEmpty()
			val value = raw.trim()
			if (value !in states) throw InvalidStatusException(raw, states)
			step.status = value
		}
		changes.title?.let { step.title = checkTitle(it) }
		changes.documentPath?.let { step.documentPath = if (it.isBlank()) null else checkPath(feature, it) }
		step.updatedAt = clock.instant()
		steps.saveAndFlush(step)
		touch(feature)
		return step to workflow
	}

	private fun touch(feature: Feature) {
		feature.updatedAt = clock.instant()
		features.saveAndFlush(feature)
	}

	private fun checkTitle(title: String?): String {
		val value = title?.trim().orEmpty()
		if (value.isEmpty() || value.length > MAX_TITLE) throw InvalidStepException("Give the step a title of up to 200 characters.")
		return value
	}

	/** A relative path to a file inside the feature's folder, with no `.`, `..` or empty segments. */
	private fun checkPath(feature: Feature, path: String): String {
		val value = path.trim()
		val segments = value.split('/')
		val valid = value.length <= MAX_PATH &&
			!value.startsWith("/") &&
			'\\' !in value &&
			segments.none { it.isEmpty() || it == "." || it == ".." } &&
			value.startsWith("${feature.folder}/")
		if (!valid) throw InvalidStepException("documentPath must be a file inside ${feature.folder}/.")
		return value
	}

	private companion object {
		const val MAX_TITLE = 200
		const val MAX_PATH = 300
	}
}
