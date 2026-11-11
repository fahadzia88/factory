package com.toptier.custom.feature.dto

import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureMessage
import com.toptier.custom.feature.domain.FeatureStep
import com.toptier.custom.feature.service.FeatureNaming
import com.toptier.custom.feature.service.FeatureView
import com.toptier.custom.feature.service.FeatureWorktree
import com.toptier.custom.feature.service.Workflow
import com.toptier.custom.feature.service.WorkflowStep
import java.time.Instant

/** Body of `POST /api/projects/{id}/features`. */
data class CreateFeatureRequest(val name: String? = null)

/** Body of `POST …/tasks/{n}/pull`: the task's name and the ACs it covers; none for row 0. */
data class OpenPullRequest(val task: String? = null, val acs: List<String>? = null)

/** Body of `POST …/features/{featureId}/tasks`: the task's folder name in `tasks/`. */
data class StartTaskRequest(val dir: String? = null)

/** Body of `PUT …/features/{featureId}/status`. */
data class SetStatusRequest(val status: String? = null)

/** Body of `POST …/features/{featureId}/steps`; [documentPath] is optional. */
data class AddStepRequest(val type: String? = null, val title: String? = null, val documentPath: String? = null)

/** Body of `PATCH …/steps/{stepId}`: the fields to change; an empty [documentPath] clears it. */
data class UpdateStepRequest(val status: String? = null, val title: String? = null, val documentPath: String? = null)

/** Body of `POST …/features/{featureId}/messages`. */
data class SendMessageRequest(val message: String? = null)

/** One stored chat message; [role] is `user` or `assistant`. */
data class FeatureMessageResponse(val id: Long, val role: String, val text: String, val createdAt: Instant) {
	companion object {
		fun from(message: FeatureMessage) =
			FeatureMessageResponse(message.id!!, message.role.name.lowercase(), message.text, message.createdAt)
	}
}

/** Whether a running turn was stopped. */
data class CancelResponse(val cancelled: Boolean)

/** A feature with its worktree, workflow and steps. */
data class FeatureResponse(
	val id: Long,
	val projectId: Long,
	val number: Int,
	/** `<NNN>-<slug>`. */
	val code: String,
	val name: String,
	val slug: String,
	val status: String,
	val branch: String,
	val folder: String,
	/** Null when no worktree has the feature's branch. */
	val worktree: FeatureWorktreeResponse?,
	val workflow: WorkflowResponse,
	val steps: List<FeatureStepResponse>,
	val createdAt: Instant,
	val updatedAt: Instant,
	/** Set only by create, when the docs pull request couldn't be opened: why. */
	val docsPullError: String? = null,
) {
	companion object {
		fun from(view: FeatureView): FeatureResponse {
			val feature = view.feature
			return FeatureResponse(
				id = feature.id!!,
				projectId = feature.projectId,
				number = feature.number,
				code = FeatureNaming.code(feature.number, feature.slug),
				name = feature.name,
				slug = feature.slug,
				status = feature.status,
				branch = feature.branch,
				folder = feature.folder,
				worktree = view.worktree?.let(FeatureWorktreeResponse::from),
				workflow = WorkflowResponse.from(view.workflow),
				steps = view.steps.map { FeatureStepResponse.from(it, view.workflow) },
				createdAt = feature.createdAt,
				updatedAt = feature.updatedAt,
			)
		}
	}
}

/** A row of a project's feature list. */
data class FeatureSummaryResponse(
	val id: Long,
	val number: Int,
	val code: String,
	val name: String,
	val status: String,
	val updatedAt: Instant,
) {
	companion object {
		fun from(feature: Feature) = FeatureSummaryResponse(
			id = feature.id!!,
			number = feature.number,
			code = FeatureNaming.code(feature.number, feature.slug),
			name = feature.name,
			status = feature.status,
			updatedAt = feature.updatedAt,
		)
	}
}

/** The worktree of a feature's branch; [missing] when its folder isn't on disk. */
data class FeatureWorktreeResponse(val id: Long, val path: String, val missing: Boolean) {
	companion object {
		fun from(worktree: FeatureWorktree) = FeatureWorktreeResponse(worktree.id, worktree.path, worktree.missing)
	}
}

/** A step, with its type's title and states from the feature's workflow. */
data class FeatureStepResponse(
	val id: Long,
	val position: Int,
	val type: String,
	val typeTitle: String,
	val title: String,
	val documentPath: String?,
	val status: String,
	val states: List<String>,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	companion object {
		fun from(step: FeatureStep, workflow: Workflow): FeatureStepResponse {
			val type = workflow.step(step.typeKey)
			return FeatureStepResponse(
				id = step.id!!,
				position = step.position,
				type = step.typeKey,
				typeTitle = type?.title ?: step.typeKey,
				title = step.title,
				documentPath = step.documentPath,
				status = step.status,
				states = type?.states ?: emptyList(),
				createdAt = step.createdAt,
				updatedAt = step.updatedAt,
			)
		}
	}
}

/** A feature's workflow, in the shape of `feature/workflow.json`. */
data class WorkflowResponse(val featureStates: List<String>, val steps: List<WorkflowStepResponse>) {
	companion object {
		fun from(workflow: Workflow) = WorkflowResponse(workflow.featureStates, workflow.steps.map(WorkflowStepResponse::from))
	}
}

/** One step type of a workflow. */
data class WorkflowStepResponse(val key: String, val title: String, val skill: String, val states: List<String>) {
	companion object {
		fun from(step: WorkflowStep) = WorkflowStepResponse(step.key, step.title, step.skill, step.states)
	}
}
