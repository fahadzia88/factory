package com.toptier.custom.ticket.controller

import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.ticket.exception.InvalidTicketException
import com.toptier.custom.ticket.exception.InvalidTicketRequestException
import com.toptier.custom.ticket.exception.TicketException
import com.toptier.custom.ticket.exception.TicketNotFoundException
import com.toptier.custom.ticket.exception.TicketProjectArchivedException
import com.toptier.custom.ticket.exception.TicketTypeFixedException
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

	/** Thrown by the project package; caught here because this advice covers only the ticket controllers. */
	@ExceptionHandler(ProjectNotFoundException::class)
	fun handleProjectNotFound(ex: ProjectNotFoundException): ProblemDetail =
		problem(HttpStatus.NOT_FOUND, "project_not_found", "Project not found.")

	@ExceptionHandler(TicketException::class)
	fun handleTicket(ex: TicketException): ProblemDetail = when (ex) {
		is TicketNotFoundException -> problem(HttpStatus.NOT_FOUND, "ticket_not_found", ex.message!!)
		is InvalidTicketException -> problem(HttpStatus.BAD_REQUEST, "invalid_ticket", ex.message!!)
		is InvalidTicketRequestException -> problem(HttpStatus.BAD_REQUEST, "invalid_request", ex.message!!)
		is TicketTypeFixedException -> problem(HttpStatus.BAD_REQUEST, "ticket_type_fixed", ex.message!!)
		is TicketProjectArchivedException -> problem(HttpStatus.CONFLICT, "project_archived", ex.message!!)
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
