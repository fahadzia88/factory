package com.toptier.custom.homelab.dto

/** `GET /api/workloads/{namespace}/{kind}/{name}`: the workload with its pods and events. */
data class WorkloadDetail(val workload: Workload, val pods: List<Pod>, val events: List<Event>)

/** `GET /api/pods/{namespace}/{name}`: the pod with its events. */
data class PodDetail(val pod: Pod, val events: List<Event>)
