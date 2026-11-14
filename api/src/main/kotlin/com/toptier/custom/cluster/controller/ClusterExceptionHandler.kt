package com.toptier.custom.cluster.controller

import com.toptier.custom.cluster.exception.ClusterException
import com.toptier.custom.cluster.exception.ClusterNotFoundException
import com.toptier.custom.cluster.exception.ClusterValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Maps cluster errors to ProblemDetail; rejected saves carry an `errors` map of field to message. */
@RestControllerAdvice(basePackageClasses = [ClusterController::class])
class ClusterExceptionHandler {

	private val log = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler(ClusterException::class)
	fun handleCluster(ex: ClusterException): ProblemDetail {
		log.debug(ex.message)
		return when (ex) {
			is ClusterValidationException ->
				ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The cluster couldn't be saved.")
					.apply { setProperty("errors", ex.errors) }
			is ClusterNotFoundException -> ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Cluster not found.")
		}
	}

	@ExceptionHandler(HttpMessageNotReadableException::class)
	fun handleUnreadable(ex: HttpMessageNotReadableException): ProblemDetail =
		ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body isn't valid JSON.")

	/** A path parameter of the wrong type, e.g. a non-numeric id. */
	@ExceptionHandler(MethodArgumentTypeMismatchException::class)
	fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail =
		ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid value for ${ex.name}.")
}
