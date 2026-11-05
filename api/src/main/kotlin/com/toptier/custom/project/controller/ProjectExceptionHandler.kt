package com.toptier.custom.project.controller

import com.toptier.custom.project.exception.ProjectArchivedException
import com.toptier.custom.project.exception.ProjectConflictException
import com.toptier.custom.project.exception.ProjectException
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.project.exception.ProjectValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Maps project errors to ProblemDetail; rejected saves carry an `errors` map of field to message. */
@RestControllerAdvice(basePackageClasses = [ProjectController::class])
class ProjectExceptionHandler {

	private val log = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler(ProjectException::class)
	fun handleProject(ex: ProjectException): ProblemDetail {
		// The caller's input, not a server failure
		if (ex is ProjectNotFoundException) log.info(ex.message) else log.debug(ex.message)
		return when (ex) {
			is ProjectValidationException -> fieldErrors(HttpStatus.BAD_REQUEST, ex.errors)
			is ProjectConflictException -> fieldErrors(HttpStatus.CONFLICT, ex.errors)
			is ProjectArchivedException -> ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message ?: "Project is archived")
			is ProjectNotFoundException -> ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Project not found.")
		}
	}

	@ExceptionHandler(HttpMessageNotReadableException::class)
	fun handleUnreadable(ex: HttpMessageNotReadableException): ProblemDetail =
		ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body isn't valid JSON.")

	private fun fieldErrors(status: HttpStatus, errors: Map<String, String>): ProblemDetail =
		ProblemDetail.forStatusAndDetail(status, "The project couldn't be saved.").apply { setProperty("errors", errors) }
}
