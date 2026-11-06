package com.toptier.custom.checkout.service

import com.toptier.custom.checkout.config.GitProperties
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Where a project's clone and worktrees live on the shared volume. */
@Component
class CheckoutPaths(props: GitProperties) {

	/** The shared volume. */
	val root: Path = Path.of(props.root)

	/** The project's clone, whose working tree is the main worktree: `<root>/<projectId>`. */
	fun checkoutDir(projectId: Long): Path = root.resolve(projectId.toString())

	/** The folder holding the project's additional worktrees: `<root>/<projectId>.worktrees`. */
	fun worktreesDir(projectId: Long): Path = root.resolve("$projectId.worktrees")

	/**
	 * The worktree folder for [branch], with "/" as "__". Callers validate branch names first; this
	 * is the last guard against a path outside the project's worktrees folder.
	 */
	fun worktreeDir(projectId: Long, branch: String): Path {
		val folder = folderName(branch)
		require(folder.isNotEmpty() && folder != "." && folder != "..") { "Not a usable branch folder: $branch" }
		val parent = worktreesDir(projectId)
		val dir = parent.resolve(folder).normalize()
		require(dir.parent == parent.normalize()) { "Not a usable branch folder: $branch" }
		return dir
	}

	/** [branch] as one folder name. */
	fun folderName(branch: String): String = branch.replace("/", "__")
}
