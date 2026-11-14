package com.toptier.custom.homelab.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/*
 * The HomeLab Controller's response types, as it sends them. Times stay ISO strings. Unknown fields are ignored and
 * optional ones are nullable, so a newer controller doesn't break the client.
 */

/** `GET /api`: the controller's mode and what it can do. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiInfo(
	val readOnly: Boolean = false,
	val namespaceSelector: String = "",
	val endpoints: ApiEndpoints? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiEndpoints(val monitoring: List<String> = emptyList(), val control: List<String> = emptyList())

/** `GET /readyz`: `ok` is false for the controller's 503 answer, with its [error]. */
data class Readiness(val ok: Boolean, val kubernetes: String? = null, val error: String? = null)

/** Replica counts of a workload. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Replicas(
	val desired: Int = 0,
	val current: Int = 0,
	val ready: Int = 0,
	val updated: Int = 0,
	val available: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImageRef(val container: String = "", val image: String = "")

/** A Deployment or StatefulSet; [kind] is `Deployment` or `StatefulSet`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Workload(
	val namespace: String = "",
	val kind: String = "",
	val name: String = "",
	val status: String = "",
	val replicas: Replicas = Replicas(),
	val images: List<ImageRef> = emptyList(),
	val restartedAt: String? = null,
	val createdAt: String? = null,
)

/** What is wrong with a pod, e.g. `ImagePullBackOff`; [message] holds the real cause. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Problem(val container: String? = null, val reason: String = "", val message: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LastTermination(val reason: String? = null, val exitCode: Int? = null, val finishedAt: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Container(
	val name: String = "",
	val image: String = "",
	val ready: Boolean = false,
	val restarts: Int = 0,
	val state: String = "unknown",
	val reason: String? = null,
	val message: String? = null,
	val exitCode: Int? = null,
	val lastTermination: LastTermination? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PodOwner(val kind: String = "", val name: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class Pod(
	val namespace: String = "",
	val name: String = "",
	val phase: String? = null,
	val ready: String = "",
	val restarts: Int = 0,
	val node: String? = null,
	val ip: String? = null,
	val owner: PodOwner? = null,
	val createdAt: String? = null,
	val startedAt: String? = null,
	val deleting: Boolean = false,
	val problem: Problem? = null,
	val containers: List<Container> = emptyList(),
	val initContainers: List<Container>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Usage(val cpuMillicores: Double? = null, val memoryMiB: Double? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Allocatable(val cpuMillicores: Double? = null, val memoryMiB: Double? = null, val pods: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NodeUsage(
	val cpuMillicores: Double? = null,
	val memoryMiB: Double? = null,
	val cpuPercent: Double? = null,
	val memoryPercent: Double? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Node(
	val name: String = "",
	val ready: Boolean = false,
	val schedulable: Boolean = true,
	val roles: List<String> = emptyList(),
	val addresses: List<String> = emptyList(),
	val kubelet: String = "",
	val os: String = "",
	val arch: String = "",
	val runtime: String = "",
	val allocatable: Allocatable = Allocatable(),
	val pressure: List<String> = emptyList(),
	val createdAt: String? = null,
	val usage: NodeUsage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Event(
	val namespace: String = "",
	val type: String = "",
	val reason: String = "",
	val message: String = "",
	val `object`: String? = null,
	val count: Int = 0,
	val lastSeen: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Namespace(
	val name: String = "",
	val phase: String = "",
	val labels: Map<String, String> = emptyMap(),
	val createdAt: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OverviewNode(
	val name: String = "",
	val ready: Boolean = false,
	val roles: List<String> = emptyList(),
	val pressure: List<String> = emptyList(),
	val usage: NodeUsage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OverviewNodes(val total: Int = 0, val ready: Int = 0, val items: List<OverviewNode> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class NotReadyWorkload(
	val namespace: String = "",
	val kind: String = "",
	val name: String = "",
	val status: String = "",
	val replicas: Replicas = Replicas(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OverviewWorkloads(
	val total: Int = 0,
	val ready: Int = 0,
	val notReady: List<NotReadyWorkload> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PodProblem(
	val namespace: String = "",
	val name: String = "",
	val restarts: Int = 0,
	val container: String? = null,
	val reason: String = "",
	val message: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OverviewPods(
	val total: Int = 0,
	val byPhase: Map<String, Int> = emptyMap(),
	val problems: List<PodProblem> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Overview(
	val kubernetes: String = "",
	val namespaces: List<String> = emptyList(),
	val nodes: OverviewNodes = OverviewNodes(),
	val workloads: OverviewWorkloads = OverviewWorkloads(),
	val pods: OverviewPods = OverviewPods(),
	val warningsLastHour: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetricsNode(val name: String = "", val allocatable: Allocatable = Allocatable(), val usage: NodeUsage? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ContainerUsage(val name: String = "", val cpuMillicores: Double? = null, val memoryMiB: Double? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PodUsage(
	val namespace: String = "",
	val name: String = "",
	val cpuMillicores: Double? = null,
	val memoryMiB: Double? = null,
	val containers: List<ContainerUsage> = emptyList(),
)

/** `GET /api/metrics`; pods are sorted by CPU, highest first. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Metrics(val nodes: List<MetricsNode> = emptyList(), val pods: List<PodUsage> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class PodRestart(
	val namespace: String = "",
	val name: String = "",
	val deleted: Boolean = true,
	val owner: String? = null,
	val message: String = "",
)

/** One object of an apply: `status` is `valid` (dry run), `applied` or `failed`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApplyObjectResult(
	val kind: String = "",
	val namespace: String? = null,
	val name: String = "",
	val status: String = "",
	val resourceVersion: String? = null,
	val error: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApplyResult(val dryRun: Boolean = false, val results: List<ApplyObjectResult> = emptyList())
