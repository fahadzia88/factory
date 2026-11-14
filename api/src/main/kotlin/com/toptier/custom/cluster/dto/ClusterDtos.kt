package com.toptier.custom.cluster.dto

import com.toptier.custom.cluster.domain.Cluster
import com.toptier.custom.cluster.service.ClusterInput
import java.time.Instant

/** Body of `POST /api/clusters` and `PUT /api/clusters/{id}`; missing fields are reported as field errors. */
data class ClusterRequest(
	val name: String? = null,
	val address: String? = null,
) {
	fun toInput() = ClusterInput(name, address)
}

/** A cluster as the API returns it. */
data class ClusterResponse(
	val id: Long,
	val name: String,
	val address: String,
	val createdAt: Instant,
) {
	companion object {
		fun from(cluster: Cluster) = ClusterResponse(
			id = requireNotNull(cluster.id) { "Cluster has not been saved" },
			name = cluster.name,
			address = cluster.address,
			createdAt = cluster.createdAt,
		)
	}
}
