package com.toptier.custom.cluster.exception

/** A cluster request that can't be carried out. */
sealed class ClusterException(message: String) : RuntimeException(message)

/** The input is invalid; [errors] maps a field (`name`, `address`) to a message for the user. */
class ClusterValidationException(val errors: Map<String, String>) :
	ClusterException("Invalid cluster: ${errors.keys.joinToString()}")

/** No cluster has this id. */
class ClusterNotFoundException(val id: Long) : ClusterException("Cluster $id not found")
