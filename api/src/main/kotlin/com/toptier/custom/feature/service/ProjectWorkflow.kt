package com.toptier.custom.feature.service

import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.project.service.ProjectService
import org.springframework.stereotype.Service
import java.nio.file.Path

/** A project's current `feature/workflow.json`, read from its checkout's main worktree. */
@Service
class ProjectWorkflow(
	private val projects: ProjectService,
	private val checkouts: RepositoryCheckoutRepository,
	private val reader: WorkflowReader,
) {

	/**
	 * The validated workflow on the checkout's default branch as last fetched. Throws
	 * `ProjectNotFoundException`, [CheckoutNotReadyException] (no checkout, or not `READY`) or
	 * `WorkflowInvalidException`.
	 */
	fun get(projectId: Long): Workflow {
		projects.get(projectId)
		val checkout = checkouts.findByProjectId(projectId)?.takeIf { it.state == CheckoutState.READY }
			?: throw CheckoutNotReadyException()
		return reader.read(Path.of(checkout.path))
	}
}
