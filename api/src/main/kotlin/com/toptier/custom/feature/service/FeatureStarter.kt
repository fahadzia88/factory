package com.toptier.custom.feature.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** A new feature, and why its docs pull request couldn't be opened (null when it was). */
data class StartedFeature(val view: FeatureView, val docsPullError: String?)

/** Creates a feature and opens its docs pull request; a failed pull request doesn't undo the feature. */
@Service
class FeatureStarter(
	private val creator: FeatureCreator,
	private val taskPulls: TaskPulls,
	private val featureService: FeatureService,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun start(projectId: Long, name: String?): StartedFeature {
		val view = creator.create(projectId, name)
		val error = try {
			taskPulls.openDocs(projectId, view.feature.id!!)
			null
		} catch (ex: Exception) {
			log.info("Feature {}: the docs pull request couldn't be opened: {}", view.feature.id, ex.message)
			if (PullProblems.covers(ex)) PullProblems.describe(ex).detail else ex.message ?: "The docs pull request couldn't be opened."
		}
		return StartedFeature(featureService.view(view.feature), error)
	}
}
