package com.toptier.custom.homelab.exception

import com.toptier.custom.homelab.dto.ApplyObjectResult

/** A call to the HomeLab Controller that failed; [status] is the controller's HTTP status when it answered. */
sealed class HomelabException(val status: Int?, message: String) : RuntimeException(message)

/** `CONTROLLER_API_TOKEN` isn't set, so no request was made. */
class HomelabNotConfiguredException :
	HomelabException(null, "The controller token isn't configured on the server. Set CONTROLLER_API_TOKEN and restart the api.")

/**
 * The controller answered with an error. [message] is its `error` text unchanged. For a failed apply (422)
 * [dryRun] and [results] say what happened to each object.
 */
class HomelabApiException(
	status: Int,
	message: String,
	val dryRun: Boolean? = null,
	val results: List<ApplyObjectResult>? = null,
) : HomelabException(status, message)

/** The controller couldn't be reached or didn't answer in time; [message] says why. */
class HomelabUnreachableException(message: String) : HomelabException(null, message)

/** The request is invalid before it is sent (for example an unknown workload kind). */
class HomelabBadRequestException(message: String) : HomelabException(400, message)
