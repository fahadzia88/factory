package com.toptier.custom.homelab.controller

import com.toptier.custom.homelab.exception.HomelabApiException
import com.toptier.custom.homelab.exception.HomelabBadRequestException
import com.toptier.custom.homelab.exception.HomelabException
import com.toptier.custom.homelab.exception.HomelabNotConfiguredException
import com.toptier.custom.homelab.exception.HomelabUnreachableException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps controller failures to ProblemDetail. `detail` is the controller's own message; `controllerStatus` is its
 * HTTP status. A controller 401 becomes 502, so the app doesn't take it for a signed-out user.
 */
@RestControllerAdvice(assignableTypes = [HomelabController::class])
class HomelabExceptionHandler {

	@ExceptionHandler(HomelabException::class)
	fun handle(ex: HomelabException): ProblemDetail = when (ex) {
		is HomelabNotConfiguredException -> ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.message!!)
		is HomelabUnreachableException ->
			ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Cluster unreachable: ${ex.message}")
		is HomelabBadRequestException -> ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message!!)
		is HomelabApiException -> api(ex)
	}

	private fun api(ex: HomelabApiException): ProblemDetail {
		val status = ex.status ?: 502
		val problem = when {
			status == 502 -> ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Cluster unreachable: ${ex.message}")
			status in PASSED_THROUGH -> ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), ex.message!!)
			else -> ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.message!!)
		}
		problem.setProperty("controllerStatus", status)
		ex.dryRun?.let { problem.setProperty("dryRun", it) }
		ex.results?.let { problem.setProperty("results", it) }
		return problem
	}

	companion object {
		private val PASSED_THROUGH = setOf(400, 403, 404, 409, 413, 422, 503)
	}
}
