package com.toptier.custom.ticket.controller

import com.toptier.custom.checkout.exception.BranchCheckedOutException
import com.toptier.custom.checkout.exception.CheckoutNotFoundException
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.checkout.exception.CheckoutProjectArchivedException
import com.toptier.custom.checkout.exception.GitCommandException
import com.toptier.custom.checkout.exception.GitNotConfiguredException
import com.toptier.custom.checkout.exception.GitRemoteException
import com.toptier.custom.checkout.exception.GitTimeoutException
import com.toptier.custom.checkout.exception.PushDefaultBranchException
import com.toptier.custom.checkout.exception.PushRejectedException
import com.toptier.custom.checkout.exception.WorktreeMissingException
import com.toptier.custom.checkout.exception.describe
import com.toptier.custom.feature.service.PullProblems
import com.toptier.custom.github.exception.GitHubException
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.ticket.exception.BranchNotPushedException
import com.toptier.custom.ticket.exception.NothingToCompareException
import com.toptier.custom.ticket.exception.PullLinkSetException
import com.toptier.custom.ticket.exception.TicketPullExistsException
import com.toptier.custom.ticket.exception.AlreadyApprovedException
import com.toptier.custom.ticket.exception.InvalidTicketException
import com.toptier.custom.ticket.exception.NoFindingsException
import com.toptier.custom.ticket.exception.NothingCommittedException
import com.toptier.custom.ticket.exception.WorkspaceMissingException
import com.toptier.custom.ticket.exception.InvalidTicketRequestException
import com.toptier.custom.ticket.exception.InvalidTransitionException
import com.toptier.custom.ticket.exception.TicketException
import com.toptier.custom.ticket.exception.TicketNotFoundException
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.TicketTypeFixedException
import com.toptier.custom.ticket.exception.WorkspaceNotNeededException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Maps ticket errors to ProblemDetail with a `code` the app and agent switch on. */
@RestControllerAdvice(basePackageClasses = [TicketController::class])
class TicketExceptionHandler {

	private val log = LoggerFactory.getLogger(javaClass)

	/** Thrown by the project package; caught here because this advice covers only the ticket controllers. */
	@ExceptionHandler(ProjectNotFoundException::class)
	fun handleProjectNotFound(ex: ProjectNotFoundException): ProblemDetail =
		problem(HttpStatus.NOT_FOUND, "project_not_found", "Project not found.")

	@ExceptionHandler(TicketException::class)
	fun handleTicket(ex: TicketException): ProblemDetail = when (ex) {
		is TicketNotFoundException -> problem(HttpStatus.NOT_FOUND, "ticket_not_found", ex.message!!)
		is InvalidTicketException -> problem(HttpStatus.BAD_REQUEST, "invalid_ticket", ex.message!!)
		is InvalidTicketRequestException -> problem(HttpStatus.BAD_REQUEST, "invalid_request", ex.message!!)
		is InvalidTransitionException -> problem(HttpStatus.CONFLICT, "invalid_transition", ex.message!!).apply {
			setProperty("from", ex.from.value)
			setProperty("to", ex.to.value)
			setProperty("allowed", ex.allowed.map { it.value })
		}
		is TicketTypeFixedException -> problem(HttpStatus.BAD_REQUEST, "ticket_type_fixed", ex.message!!)
		is TicketProjectArchivedException -> problem(HttpStatus.CONFLICT, "project_archived", ex.message!!)
		is WorkspaceNotNeededException -> problem(HttpStatus.CONFLICT, "workspace_not_needed", ex.message!!)
		is WorkspaceMissingException -> problem(HttpStatus.CONFLICT, "workspace_missing", ex.message!!)
		is NoFindingsException -> problem(HttpStatus.CONFLICT, "no_findings", ex.message!!)
		is AlreadyApprovedException -> problem(HttpStatus.CONFLICT, "already_approved", ex.message!!)
		is NothingCommittedException -> problem(HttpStatus.CONFLICT, "nothing_committed", ex.message!!)
		is BranchNotPushedException -> problem(HttpStatus.CONFLICT, "branch_not_pushed", ex.message!!)
		is NothingToCompareException -> problem(HttpStatus.CONFLICT, "nothing_to_compare", ex.message!!)
		is TicketPullExistsException -> problem(HttpStatus.CONFLICT, "pull_exists", ex.message!!)
		is PullLinkSetException -> problem(HttpStatus.CONFLICT, "pull_link_set", ex.message!!)
	}

	/** A pull request call that GitHub didn't answer usefully. */
	@ExceptionHandler(GitHubException::class)
	fun handleGitHub(ex: GitHubException): ProblemDetail {
		log.info("Ticket pull request call failed: {}", ex.message)
		val described = PullProblems.describe(ex)
		return problem(described.status, described.code, described.detail)
	}

	/** Thrown by the checkout package; the workspace keeps these on the ticket, so they reach here only from other routes. */
	@ExceptionHandler(CheckoutProjectArchivedException::class)
	fun handleCheckoutProjectArchived(ex: CheckoutProjectArchivedException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "project_archived", "This project is archived. Restore it to change its tickets.")

	@ExceptionHandler(CheckoutNotReadyException::class, CheckoutNotFoundException::class)
	fun handleCheckoutNotReady(ex: Exception): ProblemDetail = problem(
		HttpStatus.CONFLICT,
		"checkout_not_ready",
		"The project's repository checkout isn't ready. Check it out on the project page first.",
	)

	@ExceptionHandler(BranchCheckedOutException::class)
	fun handleBranchCheckedOut(ex: BranchCheckedOutException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "branch_checked_out", ex.message!!)

	@ExceptionHandler(PushRejectedException::class)
	fun handlePushRejected(ex: PushRejectedException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "push_rejected", ex.message!!)

	@ExceptionHandler(PushDefaultBranchException::class)
	fun handlePushDefaultBranch(ex: PushDefaultBranchException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "push_default_branch", ex.message!!)

	@ExceptionHandler(WorktreeMissingException::class)
	fun handleWorktreeMissing(ex: WorktreeMissingException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "workspace_missing", ex.message!!)

	@ExceptionHandler(GitRemoteException::class)
	fun handleGitRemote(ex: GitRemoteException): ProblemDetail {
		log.info("Git remote error ({}): {}", ex.failure, ex.message)
		return problem(HttpStatus.BAD_GATEWAY, "git_remote_error", ex.message!!)
	}

	@ExceptionHandler(GitNotConfiguredException::class)
	fun handleGitNotConfigured(ex: GitNotConfiguredException): ProblemDetail {
		log.info(ex.message)
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "git_not_configured", ex.message!!)
	}

	@ExceptionHandler(GitTimeoutException::class)
	fun handleGitTimeout(ex: GitTimeoutException): ProblemDetail {
		log.info(ex.message)
		return problem(HttpStatus.GATEWAY_TIMEOUT, "git_timeout", "GitHub didn't answer within ${describe(ex.timeout)}. Try again.")
	}

	/** A local git command that failed unexpectedly; git's output is logged, never returned. */
	@ExceptionHandler(GitCommandException::class)
	fun handleGitCommand(ex: GitCommandException): ProblemDetail {
		log.error("{}: {}", ex.message, ex.stderr)
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "git_error", "A git command failed on the server.")
	}

	/** A non-numeric id, an unreadable body or a missing parameter. */
	@ExceptionHandler(
		MethodArgumentTypeMismatchException::class,
		HttpMessageNotReadableException::class,
		MissingServletRequestParameterException::class,
	)
	fun handleInvalidRequest(ex: Exception): ProblemDetail {
		val detail = when (ex) {
			is MethodArgumentTypeMismatchException -> "Invalid value for ${ex.name}."
			is MissingServletRequestParameterException -> "Missing parameter ${ex.parameterName}."
			else -> "The request body isn't valid JSON."
		}
		return problem(HttpStatus.BAD_REQUEST, "invalid_request", detail)
	}

	private fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
		ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty("code", code) }
}
