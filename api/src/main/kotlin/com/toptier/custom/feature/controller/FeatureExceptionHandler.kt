package com.toptier.custom.feature.controller

import com.toptier.custom.feature.exception.FeatureException
import com.toptier.custom.feature.exception.FeatureNotFoundException
import com.toptier.custom.feature.exception.InvalidFeatureNameException
import com.toptier.custom.feature.exception.WorkflowInvalidException
import com.toptier.custom.project.exception.ProjectNotFoundException
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

	/** Thrown by the project package; caught here because this advice covers only the feature controllers. */
	@ExceptionHandler(ProjectNotFoundException::class)
	fun handleProjectNotFound(ex: ProjectNotFoundException): ProblemDetail =
		problem(HttpStatus.NOT_FOUND, "project_not_found", "Project not found.")

	@ExceptionHandler(FeatureException::class)
	fun handleFeature(ex: FeatureException): ProblemDetail = when (ex) {
		is FeatureNotFoundException -> problem(HttpStatus.NOT_FOUND, "feature_not_found", ex.message!!)
		is InvalidFeatureNameException -> problem(HttpStatus.BAD_REQUEST, "invalid_name", ex.message!!)
		is WorkflowInvalidException -> problem(HttpStatus.CONFLICT, "workflow_invalid", ex.message!!)
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
