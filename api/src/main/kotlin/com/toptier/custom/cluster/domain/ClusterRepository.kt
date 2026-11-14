package com.toptier.custom.cluster.domain

import org.springframework.data.jpa.repository.JpaRepository

/** Stores [Cluster]s; the existence checks back the name uniqueness rule. */
interface ClusterRepository : JpaRepository<Cluster, Long> {

	/** Every cluster, ordered by name ignoring case. */
	fun findAllByOrderByNameKeyAscIdAsc(): List<Cluster>

	/** True when any cluster has this lowercased name. */
	fun existsByNameKey(nameKey: String): Boolean

	/** As [existsByNameKey], ignoring the cluster being updated. */
	fun existsByNameKeyAndIdNot(nameKey: String, id: Long): Boolean
}
