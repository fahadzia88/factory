package com.toptier.custom.homelab.service

import com.toptier.custom.homelab.dto.ApiInfo
import com.toptier.custom.homelab.dto.ApplyResult
import com.toptier.custom.homelab.dto.Event
import com.toptier.custom.homelab.dto.Metrics
import com.toptier.custom.homelab.dto.Namespace
import com.toptier.custom.homelab.dto.Node
import com.toptier.custom.homelab.dto.Overview
import com.toptier.custom.homelab.dto.Pod
import com.toptier.custom.homelab.dto.PodDetail
import com.toptier.custom.homelab.dto.PodRestart
import com.toptier.custom.homelab.dto.Readiness
import com.toptier.custom.homelab.dto.Workload
import com.toptier.custom.homelab.dto.WorkloadDetail
import com.toptier.custom.homelab.exception.HomelabApiException
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference

/**
 * One function per HomeLab Controller endpoint (the two streams are in `HomelabWatch` and `HomelabLogs`). Each takes
 * the controller's [address]; failures are the exceptions of `HomelabErrors`, carrying the controller's own message.
 */
@Service
class HomelabClient(private val call: HomelabCall) {

	/** True when `GET /healthz` says the controller process is up. */
	fun health(address: String): Boolean =
		get<Map<String, String>>(address, "/healthz")["status"] == "ok"

	/** `GET /readyz`; the controller's 503 ("can't reach Kubernetes") is an answer, not an error. */
	fun ready(address: String): Readiness =
		try {
			val body = get<Map<String, String>>(address, "/readyz")
			Readiness(ok = body["status"] == "ok", kubernetes = body["kubernetes"], error = body["error"])
		} catch (e: HomelabApiException) {
			if (e.status != 503) throw e
			Readiness(ok = false, error = e.message)
		}

	/** `GET /api`: read-only mode, namespace selector and the endpoint lists. */
	fun info(address: String): ApiInfo = get(address, "/api")

	fun overview(address: String): Overview = get(address, "/api/overview")

	fun namespaces(address: String): List<Namespace> = get(address, "/api/namespaces")

	fun nodes(address: String): List<Node> = get(address, "/api/nodes")

	fun workloads(address: String, namespace: String? = null): List<Workload> =
		get(address, "/api/workloads", query = mapOf("namespace" to namespace))

	fun workload(address: String, namespace: String, kind: WorkloadKind, name: String): WorkloadDetail {
		val body = call.text(address, HomelabRequest(HttpMethod.GET, WORKLOAD, workloadVars(namespace, kind, name)))
		val tree = call.tree(body)
		return WorkloadDetail(
			workload = call.parse(body, typeRef<Workload>()),
			pods = tree.get("pods")?.let { call.parse(it.toString(), typeRef<List<Pod>>()) } ?: emptyList(),
			events = tree.get("events")?.let { call.parse(it.toString(), typeRef<List<Event>>()) } ?: emptyList(),
		)
	}

	fun pods(address: String, namespace: String? = null, problemsOnly: Boolean = false): List<Pod> =
		get(address, "/api/pods", query = mapOf("namespace" to namespace, "problems" to if (problemsOnly) true else null))

	fun pod(address: String, namespace: String, name: String): PodDetail {
		val body = call.text(
			address,
			HomelabRequest(HttpMethod.GET, "/api/pods/{namespace}/{name}", mapOf("namespace" to namespace, "name" to name)),
		)
		val tree = call.tree(body)
		return PodDetail(
			pod = call.parse(body, typeRef<Pod>()),
			events = tree.get("events")?.let { call.parse(it.toString(), typeRef<List<Event>>()) } ?: emptyList(),
		)
	}

	/** Events, newest first; [type] is `Normal` or `Warning`. The controller defaults [limit] to 100 and caps it at 1000. */
	fun events(address: String, namespace: String? = null, type: String? = null, limit: Int? = null): List<Event> =
		get(address, "/api/events", query = mapOf("namespace" to namespace, "type" to type, "limit" to limit))

	/** Needs metrics-server; the controller answers 503 otherwise. */
	fun metrics(address: String, namespace: String? = null): Metrics =
		get(address, "/api/metrics", query = mapOf("namespace" to namespace))

	fun restart(address: String, namespace: String, kind: WorkloadKind, name: String): Workload =
		post(address, "$WORKLOAD/restart", workloadVars(namespace, kind, name))

	fun scale(address: String, namespace: String, kind: WorkloadKind, name: String, replicas: Int): Workload =
		post(address, "$WORKLOAD/scale", workloadVars(namespace, kind, name), json = mapOf("replicas" to replicas))

	/** [container] is needed only when the pod has more than one. */
	fun setImage(
		address: String,
		namespace: String,
		kind: WorkloadKind,
		name: String,
		image: String,
		container: String? = null,
	): Workload {
		val body = linkedMapOf<String, String>("image" to image)
		container?.let { body["container"] = it }
		return post(address, "$WORKLOAD/image", workloadVars(namespace, kind, name), json = body)
	}

	/** Deletes the pod so its owner replaces it; [force] also deletes a pod with no owner. */
	fun restartPod(address: String, namespace: String, name: String, force: Boolean = false): PodRestart =
		post(
			address, "/api/pods/{namespace}/{name}/restart", mapOf("namespace" to namespace, "name" to name),
			query = mapOf("force" to if (force) true else null),
		)

	/** Applies raw YAML or JSON; with [dryRun] nothing is changed. A failed apply is a 422 [HomelabApiException]. */
	fun apply(address: String, body: String, namespace: String? = null, dryRun: Boolean = false): ApplyResult =
		call.call(
			address,
			HomelabRequest(
				HttpMethod.POST, "/api/apply",
				query = mapOf("namespace" to namespace, "dryRun" to if (dryRun) true else null),
				text = body,
			),
			typeRef(),
		)

	private inline fun <reified T> get(address: String, path: String, query: Map<String, Any?> = emptyMap()): T =
		call.call(address, HomelabRequest(HttpMethod.GET, path, query = query), typeRef())

	private inline fun <reified T> post(
		address: String,
		path: String,
		vars: Map<String, String> = emptyMap(),
		query: Map<String, Any?> = emptyMap(),
		json: Any? = null,
	): T = call.call(address, HomelabRequest(HttpMethod.POST, path, vars, query, json), typeRef())

	private fun workloadVars(namespace: String, kind: WorkloadKind, name: String) =
		mapOf("namespace" to namespace, "kind" to kind.path, "name" to name)

	private companion object {
		const val WORKLOAD = "/api/workloads/{namespace}/{kind}/{name}"
	}
}

/** A [TypeReference] for [T], keeping its generic arguments. */
internal inline fun <reified T> typeRef(): TypeReference<T> = object : TypeReference<T>() {}
