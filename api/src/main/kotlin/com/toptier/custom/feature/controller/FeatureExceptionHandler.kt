package com.toptier.custom.feature.controller

import com.toptier.custom.checkout.exception.BranchCheckedOutException
import com.toptier.custom.checkout.exception.CheckoutNotFoundException
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.checkout.exception.CheckoutProjectArchivedException
import com.toptier.custom.checkout.exception.GitCommandException
import com.toptier.custom.checkout.exception.GitNotConfiguredException
import com.toptier.custom.checkout.exception.GitRemoteException
import com.toptier.custom.checkout.exception.GitTimeoutException
import com.toptier.custom.checkout.exception.describe
import com.toptier.custom.feature.exception.FeatureException
import com.toptier.custom.feature.exception.FeatureNotFoundException
import com.toptier.custom.feature.exception.FeatureProjectArchivedException
import com.toptier.custom.feature.exception.InvalidFeatureNameException
import com.toptier.custom.feature.exception.WorkflowInvalidException
import com.toptier.custom.project.exception.ProjectNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Maps feature errors to ProblemDetail with a `code` the app and agent switch on. Covers every controller in the feature package. */
@RestControllerAdvice(basePackageClasses = [FeatureController::class])
class FeatureExceptionHandler {

	private val log = LoggerFactory.getLogger(javaClass)

	/** Thrown by the project package; caught here because this advice covers only the feature controllers. */
	@ExceptionHandler(ProjectNotFoundException::class)
	fun handleProjectNotFound(ex: ProjectNotFoundException): ProblemDetail =
		problem(HttpStatus.NOT_FOUND, "project_not_found", "Project not found.")

	@ExceptionHandler(FeatureException::class)
	fun handleFeature(ex: FeatureException): ProblemDetail = when (ex) {
		is FeatureNotFoundException -> problem(HttpStatus.NOT_FOUND, "feature_not_found", ex.message!!)
		// errors.name lets the form show the message on its field
		is InvalidFeatureNameException -> problem(HttpStatus.BAD_REQUEST, "invalid_name", ex.message!!)
			.apply { setProperty("errors", mapOf("name" to ex.message)) }
		is WorkflowInvalidException -> problem(HttpStatus.CONFLICT, "workflow_invalid", ex.message!!)
		is FeatureProjectArchivedException -> problem(HttpStatus.CONFLICT, "project_archived", ex.message!!)
	}

	/** Thrown by the checkout package while a feature is created. */
	@ExceptionHandler(CheckoutProjectArchivedException::class)
	fun handleCheckoutProjectArchived(ex: CheckoutProjectArchivedException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "project_archived", "This project is archived. Restore it to start a feature.")

	@ExceptionHandler(CheckoutNotReadyException::class, CheckoutNotFoundException::class)
	fun handleCheckoutNotReady(ex: Exception): ProblemDetail = problem(
		HttpStatus.CONFLICT,
		"checkout_not_ready",
		"The project's repository checkout isn't ready. Check it out on the project page first.",
	)

	@ExceptionHandler(BranchCheckedOutException::class)
	fun handleBranchCheckedOut(ex: BranchCheckedOutException): ProblemDetail =
		problem(HttpStatus.CONFLICT, "branch_checked_out", ex.message!!)

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
