package com.toptier.custom.checkout.controller

import com.toptier.custom.checkout.dto.UnsavedWorkResponse
import com.toptier.custom.checkout.exception.BaseNotFoundException
import com.toptier.custom.checkout.exception.BranchCheckedOutException
import com.toptier.custom.checkout.exception.CheckoutExistsException
import com.toptier.custom.checkout.exception.CheckoutNotFoundException
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.checkout.exception.CheckoutProjectArchivedException
import com.toptier.custom.checkout.exception.GitCommandException
import com.toptier.custom.checkout.exception.GitNotConfiguredException
import com.toptier.custom.checkout.exception.GitRemoteException
import com.toptier.custom.checkout.exception.GitTimeoutException
import com.toptier.custom.checkout.exception.InvalidBranchException
import com.toptier.custom.checkout.exception.MainWorktreeException
import com.toptier.custom.checkout.exception.PushDefaultBranchException
import com.toptier.custom.checkout.exception.PushRejectedException
import com.toptier.custom.checkout.exception.WorktreeMissingException
import com.toptier.custom.checkout.exception.WorktreeNotFoundException
import com.toptier.custom.checkout.exception.WorktreeUnsavedException
import com.toptier.custom.checkout.exception.describe
import com.toptier.custom.project.exception.ProjectNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Maps checkout and git errors to ProblemDetail with a `code` the app and agent switch on. Covers
 * every controller in the checkout package.
 */
@RestControllerAdvice(basePackageClasses = [CheckoutController::class])
class CheckoutExceptionHandler {

	private val log = LoggerFactory.getLogger(javaClass)

	/** Thrown by the project package; caught here because this advice covers only the checkout controllers. */
	@ExceptionHandler(ProjectNotFoundException::class)
	fun handleProjectNotFound(ex: ProjectNotFoundException): ProblemDetail =
		problem(HttpStatus.NOT_FOUND, "project_not_found", "Project not found.")

	@ExceptionHandler(CheckoutNotFoundException::class)
	fun handleCheckoutNotFound(ex: CheckoutNotFoundException): ProblemDetail =
		problem(HttpStatus.NOT_FOUND, "checkout_not_found", ex.message!!)

	@ExceptionHandler(CheckoutExistsException::class)
	fun handleCheckoutExists(ex: CheckoutExistsException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "checkout_exists", ex.message!!)

	@ExceptionHandler(CheckoutNotReadyException::class)
	fun handleCheckoutNotReady(ex: CheckoutNotReadyException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "checkout_not_ready", ex.message!!)

	@ExceptionHandler(CheckoutProjectArchivedException::class)
	fun handleProjectArchived(ex: CheckoutProjectArchivedException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "project_archived", ex.message!!)

	@ExceptionHandler(InvalidBranchException::class)
	fun handleInvalidBranch(ex: InvalidBranchException): ProblemDetail =
		problem(HttpStatus.BAD_REQUEST, "invalid_branch", ex.message!!)

	@ExceptionHandler(BaseNotFoundException::class)
	fun handleBaseNotFound(ex: BaseNotFoundException): ProblemDetail =
		problem(HttpStatus.BAD_REQUEST, "base_not_found", ex.message!!)

	@ExceptionHandler(BranchCheckedOutException::class)
	fun handleBranchCheckedOut(ex: BranchCheckedOutException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "branch_checked_out", ex.message!!)

	@ExceptionHandler(WorktreeNotFoundException::class)
	fun handleWorktreeNotFound(ex: WorktreeNotFoundException): ProblemDetail =
		problem(HttpStatus.NOT_FOUND, "worktree_not_found", ex.message!!)

	@ExceptionHandler(MainWorktreeException::class)
	fun handleMainWorktree(ex: MainWorktreeException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "main_worktree", ex.message!!)

	/** The problem carries the work that would be lost as `unsaved`. */
	@ExceptionHandler(WorktreeUnsavedException::class)
	fun handleWorktreeUnsaved(ex: WorktreeUnsavedException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "worktree_unsaved", ex.message!!)
			.apply { setProperty("unsaved", UnsavedWorkResponse.from(ex.unsaved)) }

	@ExceptionHandler(PushDefaultBranchException::class)
	fun handlePushDefaultBranch(ex: PushDefaultBranchException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "push_default_branch", ex.message!!)

	@ExceptionHandler(PushRejectedException::class)
	fun handlePushRejected(ex: PushRejectedException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "push_rejected", ex.message!!)

	@ExceptionHandler(WorktreeMissingException::class)
	fun handleWorktreeMissing(ex: WorktreeMissingException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "worktree_missing", ex.message!!)

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

	/** A local git command that failed unexpectedly; git's output is logged, never returned. */
	@ExceptionHandler(GitCommandException::class)
	fun handleGitCommand(ex: GitCommandException): ProblemDetail {
		log.error("{}: {}", ex.message, ex.stderr)
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "git_error", "A git command failed on the server.")
	}

	private fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
		ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty("code", code) }
}
