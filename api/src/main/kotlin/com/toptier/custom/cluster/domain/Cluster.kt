package com.toptier.custom.cluster.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** A Kubernetes cluster managed by a HomeLab Controller: a name and the controller's address. */
@Entity
@Table(name = "cluster")
class Cluster {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null

	@Column(nullable = false, length = 100)
	var name: String = ""

	/** [name] lowercased, so names are unique ignoring case. */
	@Column(name = "name_key", nullable = false, unique = true, length = 100)
	var nameKey: String = ""

	/** Base URL of the controller (`http` or `https`), stored without a trailing slash. */
	@Column(nullable = false, length = 300)
	var address: String = ""

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.EPOCH

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = Instant.EPOCH
}
