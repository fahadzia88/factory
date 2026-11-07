package com.toptier.custom.feature.service

import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** One step type of a project's workflow, run by the skill in `.claude/skills/<skill>/`; [states] start with the initial one. */
data class WorkflowStep(val key: String, val title: String, val skill: String, val states: List<String>)

/** A project's feature workflow, from its `feature/workflow.json`; [featureStates] start with the initial one. */
data class Workflow(val featureStates: List<String>, val steps: List<WorkflowStep>) {

	/** The state a new feature starts in. */
	@get:JsonIgnore
	val initialState: String get() = featureStates.first()

	/** The step type with [key], or null. */
	fun step(key: String): WorkflowStep? = steps.firstOrNull { it.key == key }
}

/** Stores a [Workflow] as JSON in the `feature.workflow` column. */
@Component
class WorkflowCodec(private val jsonMapper: JsonMapper) {

	/** The workflow as JSON, in the file's shape. */
	fun write(workflow: Workflow): String = jsonMapper.writeValueAsString(workflow)

	/** A stored workflow, parsed and checked as the file is, so it round-trips exactly. */
	fun read(json: String): Workflow = WorkflowParser.parse(jsonMapper, json)
}
