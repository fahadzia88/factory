package com.toptier.custom.homelab.service

import com.toptier.custom.homelab.exception.HomelabBadRequestException

/** The workload kinds the controller manages, with the path segment it expects for each. */
enum class WorkloadKind(val path: String, val label: String) {
	DEPLOYMENTS("deployments", "Deployment"),
	STATEFULSETS("statefulsets", "StatefulSet");

	companion object {
		/** The kind for a path segment: `deployments`, `statefulsets` or their aliases `deploy` and `sts`; else a 400. */
		fun fromPath(value: String): WorkloadKind = when (value.lowercase()) {
			"deployments", "deploy" -> DEPLOYMENTS
			"statefulsets", "sts" -> STATEFULSETS
			else -> throw HomelabBadRequestException("Invalid kind \"$value\". Use deployments or statefulsets.")
		}

		/** The kind for the `kind` a response reports (`Deployment` or `StatefulSet`). */
		fun fromResponse(value: String): WorkloadKind = when (value) {
			"Deployment" -> DEPLOYMENTS
			"StatefulSet" -> STATEFULSETS
			else -> throw HomelabBadRequestException("Unknown workload kind \"$value\".")
		}
	}
}
