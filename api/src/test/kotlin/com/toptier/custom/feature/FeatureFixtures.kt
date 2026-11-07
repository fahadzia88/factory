package com.toptier.custom.feature

import com.toptier.custom.checkout.GitFixtures.bareRemote
import com.toptier.custom.checkout.GitFixtures.git
import com.toptier.custom.checkout.GitFixtures.scratchClone
import java.nio.file.Files
import java.nio.file.Path

/** Bare remotes seeded with a feature workflow, standing in for a project repository on GitHub. */
object FeatureFixtures {

	const val WORKFLOW = """{
  "featureStates": ["requirements", "breakdown", "specs", "implementation", "done", "abandoned"],
  "steps": [
    { "key": "requirements", "title": "Requirements", "skill": "feature-requirements", "states": ["draft", "approved"] },
    { "key": "breakdown", "title": "Task breakdown", "skill": "feature-breakdown", "states": ["draft", "approved"] },
    { "key": "spec", "title": "Task spec", "skill": "feature-task-spec", "states": ["draft", "approved"] }
  ]
}
"""

	const val TEMPLATE = "---\nfeature: <NNN>-<slug>\nstatus: draft\n---\n\n# <Feature name>\n\n## Changelog\n\n- <date> — Created.\n"

	/**
	 * A bare remote whose `main` has the workflow, its three skills and the requirements template;
	 * [files] are committed too (path → content), and [skip] leaves out seeded paths.
	 */
	fun seededRemote(parent: Path, files: Map<String, String> = emptyMap(), skip: Set<String> = emptySet()): Path {
		val remote = bareRemote(parent)
		val scratch = scratchClone(parent, remote)
		val seeded = mapOf(
			"feature/workflow.json" to WORKFLOW,
			".claude/skills/feature-requirements/SKILL.md" to "# Requirements\n",
			".claude/skills/feature-breakdown/SKILL.md" to "# Breakdown\n",
			".claude/skills/feature-task-spec/SKILL.md" to "# Task spec\n",
			"feature/_templates/requirements.md" to TEMPLATE,
		).filterKeys { it !in skip } + files
		for ((path, content) in seeded) {
			val target = scratch.resolve(path)
			Files.createDirectories(target.parent)
			Files.writeString(target, content)
		}
		git(scratch, "add", "-A")
		git(scratch, "commit", "-m", "Seed the feature workflow")
		git(scratch, "push", "origin", "main")
		return remote
	}
}
