package com.toptier.custom.feature.service

import com.toptier.custom.feature.exception.WorkflowInvalidException
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/** Reads and validates a project's `feature/workflow.json` and requirements template from a worktree. */
@Component
class WorkflowReader(private val jsonMapper: JsonMapper) {

	/** The validated workflow in [worktree]; throws [WorkflowInvalidException] naming the problem. */
	fun read(worktree: Path): Workflow {
		val file = worktree.resolve(WORKFLOW_FILE)
		if (!Files.isRegularFile(file)) {
			throw WorkflowInvalidException("$WORKFLOW_FILE: the file is missing. Add it to the repository's default branch.")
		}
		if (Files.size(file) > MAX_BYTES) throw WorkflowInvalidException("$WORKFLOW_FILE: is larger than 64 KB.")
		val workflow = WorkflowParser.parse(jsonMapper, Files.readString(file))
		workflow.steps.forEachIndexed { i, step ->
			if (!Files.isRegularFile(worktree.resolve(".claude/skills/${step.skill}/SKILL.md"))) {
				throw WorkflowInvalidException(
					"$WORKFLOW_FILE: steps[$i].skill \"${step.skill}\" has no .claude/skills/${step.skill}/SKILL.md.",
				)
			}
		}
		return workflow
	}

	/** The requirements template's text in [worktree]; throws [WorkflowInvalidException] when it's missing. */
	fun requireTemplate(worktree: Path): String {
		val file = worktree.resolve(TEMPLATE_FILE)
		if (!Files.isRegularFile(file)) {
			throw WorkflowInvalidException("$TEMPLATE_FILE: the file is missing. Add it to the repository's default branch.")
		}
		return Files.readString(file)
	}

	companion object {
		const val WORKFLOW_FILE = "feature/workflow.json"
		const val TEMPLATE_FILE = "feature/_templates/requirements.md"
		private const val MAX_BYTES = 64 * 1024L
	}
}

/** Parses workflow JSON by walking the tree, so every problem names its path. */
internal object WorkflowParser {

	private val NAME = Regex("^[a-z][a-z0-9-]{0,39}$")

	fun parse(jsonMapper: JsonMapper, text: String): Workflow {
		val root = try {
			jsonMapper.readTree(text)
		} catch (ex: JacksonException) {
			val line = ex.location?.lineNr?.takeIf { it > 0 }
			throw invalid("isn't valid JSON" + (line?.let { " (line $it)" } ?: "") + ".")
		}
		if (root == null || !root.isObject) throw invalid("must be a JSON object.")

		val featureStates = states(root.get("featureStates"), "featureStates")
		val stepsNode = root.get("steps")
		if (stepsNode == null || !stepsNode.isArray || stepsNode.isEmpty) {
			throw invalid("steps must be a non-empty list of steps.")
		}
		val steps = stepsNode.mapIndexed { i, node -> step(node, "steps[$i]") }
		steps.groupingBy { it.key }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
			throw invalid("steps has the key \"${it.key}\" twice.")
		}
		return Workflow(featureStates, steps)
	}

	private fun step(node: JsonNode, path: String): WorkflowStep {
		if (!node.isObject) throw invalid("$path must be an object.")
		val key = name(node.get("key"), "$path.key")
		val titleNode = node.get("title")
		val title = titleNode?.takeIf { it.isString }?.asString()?.trim().orEmpty()
		if (title.isEmpty() || title.length > 100) throw invalid("$path.title must be text of 1 to 100 characters.")
		val skill = name(node.get("skill"), "$path.skill")
		return WorkflowStep(key, title, skill, states(node.get("states"), "$path.states"))
	}

	private fun states(node: JsonNode?, path: String): List<String> {
		if (node == null || !node.isArray || node.isEmpty) throw invalid("$path must be a non-empty list of states.")
		val states = node.mapIndexed { i, state -> name(state, "$path[$i]") }
		states.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
			throw invalid("$path has \"${it.key}\" twice.")
		}
		return states
	}

	private fun name(node: JsonNode?, path: String): String {
		val value = node?.takeIf { it.isString }?.asString()
		if (value == null || !NAME.matches(value)) {
			throw invalid("$path \"${value ?: node?.toString() ?: ""}\" must be lowercase letters, digits and dashes, up to 40 characters.")
		}
		return value
	}

	private fun invalid(problem: String) = WorkflowInvalidException("${WorkflowReader.WORKFLOW_FILE}: $problem")
}
