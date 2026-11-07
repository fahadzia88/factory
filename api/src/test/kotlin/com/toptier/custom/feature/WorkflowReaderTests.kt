package com.toptier.custom.feature

import com.toptier.custom.feature.exception.WorkflowInvalidException
import com.toptier.custom.feature.service.Workflow
import com.toptier.custom.feature.service.WorkflowCodec
import com.toptier.custom.feature.service.WorkflowReader
import com.toptier.custom.feature.service.WorkflowStep
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class WorkflowReaderTests {

	@TempDir
	lateinit var worktree: Path

	private val jsonMapper = JsonMapper.builder().build()
	private val reader = WorkflowReader(jsonMapper)

	@BeforeEach
	fun skills() {
		for (skill in listOf("feature-requirements", "feature-breakdown", "feature-task-spec")) {
			Files.createDirectories(worktree.resolve(".claude/skills/$skill"))
			Files.writeString(worktree.resolve(".claude/skills/$skill/SKILL.md"), "# $skill")
		}
	}

	@Test
	fun `reads the contract's example`() {
		write(EXAMPLE)

		val workflow = reader.read(worktree)

		assertEquals(EXPECTED, workflow)
		assertEquals("requirements", workflow.initialState)
		assertEquals("Task spec", workflow.step("spec")?.title)
	}

	@Test
	fun `ignores unknown properties`() {
		write(EXAMPLE.replaceFirst("{", "{\n  \"version\": 2,"))

		assertEquals(EXPECTED, reader.read(worktree))
	}

	@Test
	fun `a missing file is refused`() {
		assertProblem("feature/workflow.json: the file is missing. Add it to the repository's default branch.")
	}

	@Test
	fun `bad JSON is refused with its line`() {
		write("{\n  \"featureStates\": [\n}")

		val ex = assertThrows<WorkflowInvalidException> { reader.read(worktree) }
		assert(ex.message!!.startsWith("feature/workflow.json: isn't valid JSON (line ")) { ex.message!! }
	}

	@Test
	fun `a root that isn't an object is refused`() {
		write("[]")

		assertProblem("feature/workflow.json: must be a JSON object.")
	}

	@Test
	fun `empty feature states are refused`() {
		write(EXAMPLE.replace(FEATURE_STATES, "[]"))

		assertProblem("feature/workflow.json: featureStates must be a non-empty list of states.")
	}

	@Test
	fun `empty steps are refused`() {
		write("""{ "featureStates": ["a"], "steps": [] }""")

		assertProblem("feature/workflow.json: steps must be a non-empty list of steps.")
	}

	@Test
	fun `a state that isn't lowercase is refused`() {
		write(EXAMPLE.replace("\"draft\", \"approved\"] },\n    { \"key\": \"breakdown\"", "\"Draft\", \"approved\"] },\n    { \"key\": \"breakdown\""))

		assertProblem(
			"feature/workflow.json: steps[0].states[0] \"Draft\" must be lowercase letters, digits and dashes, up to 40 characters.",
		)
	}

	@Test
	fun `a duplicate state is refused`() {
		write(EXAMPLE.replace(FEATURE_STATES, """["requirements", "done", "done"]"""))

		assertProblem("feature/workflow.json: featureStates has \"done\" twice.")
	}

	@Test
	fun `duplicate step keys are refused`() {
		write(EXAMPLE.replace("\"key\": \"breakdown\"", "\"key\": \"requirements\""))

		assertProblem("feature/workflow.json: steps has the key \"requirements\" twice.")
	}

	@Test
	fun `a blank title is refused`() {
		write(EXAMPLE.replace("\"title\": \"Task breakdown\"", "\"title\": \"  \""))

		assertProblem("feature/workflow.json: steps[1].title must be text of 1 to 100 characters.")
	}

	@Test
	fun `a title over 100 characters is refused`() {
		write(EXAMPLE.replace("\"title\": \"Task breakdown\"", "\"title\": \"${"x".repeat(101)}\""))

		assertProblem("feature/workflow.json: steps[1].title must be text of 1 to 100 characters.")
	}

	@Test
	fun `a step without states is refused`() {
		write(EXAMPLE.replace("\"skill\": \"feature-task-spec\", \"states\": [\"draft\", \"approved\"]", "\"skill\": \"feature-task-spec\", \"states\": []"))

		assertProblem("feature/workflow.json: steps[2].states must be a non-empty list of states.")
	}

	@Test
	fun `a skill without SKILL_md is refused`() {
		write(EXAMPLE.replace("feature-breakdown", "feature-plan"))

		assertProblem("feature/workflow.json: steps[1].skill \"feature-plan\" has no .claude/skills/feature-plan/SKILL.md.")
	}

	@Test
	fun `a file over 64 KB is refused`() {
		write(EXAMPLE + " ".repeat(65 * 1024))

		assertProblem("feature/workflow.json: is larger than 64 KB.")
	}

	@Test
	fun `requireTemplate returns the template or refuses when it's missing`() {
		val ex = assertThrows<WorkflowInvalidException> { reader.requireTemplate(worktree) }
		assertEquals("feature/_templates/requirements.md: the file is missing. Add it to the repository's default branch.", ex.message)

		Files.createDirectories(worktree.resolve("feature/_templates"))
		Files.writeString(worktree.resolve("feature/_templates/requirements.md"), "# <Feature name>\n")
		assertEquals("# <Feature name>\n", reader.requireTemplate(worktree))
	}

	@Test
	fun `the codec round-trips a workflow`() {
		val codec = WorkflowCodec(jsonMapper)

		val json = codec.write(EXPECTED)

		assertEquals(EXPECTED, codec.read(json))
		assert("initialState" !in json) { json }
	}

	private fun write(text: String) {
		Files.createDirectories(worktree.resolve("feature"))
		Files.writeString(worktree.resolve("feature/workflow.json"), text)
	}

	private fun assertProblem(message: String) {
		val ex = assertThrows<WorkflowInvalidException> { reader.read(worktree) }
		assertEquals(message, ex.message)
	}

	private companion object {
		const val FEATURE_STATES = """["requirements", "breakdown", "specs", "implementation", "done", "abandoned"]"""
		val EXAMPLE = """
			{
			  "featureStates": $FEATURE_STATES,
			  "steps": [
			    { "key": "requirements", "title": "Requirements", "skill": "feature-requirements", "states": ["draft", "approved"] },
			    { "key": "breakdown", "title": "Task breakdown", "skill": "feature-breakdown", "states": ["draft", "approved"] },
			    { "key": "spec", "title": "Task spec", "skill": "feature-task-spec", "states": ["draft", "approved"] }
			  ]
			}
		""".trimIndent()
		val EXPECTED = Workflow(
			listOf("requirements", "breakdown", "specs", "implementation", "done", "abandoned"),
			listOf(
				WorkflowStep("requirements", "Requirements", "feature-requirements", listOf("draft", "approved")),
				WorkflowStep("breakdown", "Task breakdown", "feature-breakdown", listOf("draft", "approved")),
				WorkflowStep("spec", "Task spec", "feature-task-spec", listOf("draft", "approved")),
			),
		)
	}
}
