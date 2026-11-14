package com.toptier.custom.cluster.service

import com.toptier.custom.cluster.domain.Cluster
import com.toptier.custom.cluster.domain.ClusterRepository
import com.toptier.custom.cluster.exception.ClusterNotFoundException
import com.toptier.custom.cluster.exception.ClusterValidationException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.URISyntaxException
import java.time.Clock
import java.util.Locale

/** A cluster as submitted for create or update, before validation; every field may be missing. */
data class ClusterInput(val name: String?, val address: String?)

/** Lists, creates, updates and deletes clusters, enforcing validation and unique names. */
@Service
@Transactional
class ClusterService(
	private val repository: ClusterRepository,
	private val clock: Clock,
	private val events: ApplicationEventPublisher,
) {

	/** Every cluster, ordered by name. */
	@Transactional(readOnly = true)
	fun list(): List<Cluster> = repository.findAllByOrderByNameKeyAscIdAsc()

	/** The cluster with this id. */
	@Transactional(readOnly = true)
	fun get(id: Long): Cluster = repository.findById(id).orElseThrow { ClusterNotFoundException(id) }

	/** Creates a cluster. */
	fun create(input: ClusterInput): Cluster {
		val valid = validate(input, excludeId = null)
		val now = clock.instant()
		val cluster = Cluster().apply {
			assign(valid)
			createdAt = now
			updatedAt = now
		}
		return save(cluster)
	}

	/** Replaces a cluster's name and address. */
	fun update(id: Long, input: ClusterInput): Cluster {
		val cluster = get(id)
		val valid = validate(input, excludeId = id)
		cluster.assign(valid)
		cluster.updatedAt = clock.instant()
		return save(cluster)
	}

	/** Removes a cluster and publishes a [ClusterDeletedEvent] so projects linked to it are unlinked. */
	fun delete(id: Long) {
		val cluster = get(id)
		repository.delete(cluster)
		repository.flush()
		events.publishEvent(ClusterDeletedEvent(id))
	}

	private fun Cluster.assign(valid: ValidInput) {
		name = valid.name
		nameKey = valid.nameKey
		address = valid.address
	}

	/** Flushes so a unique-constraint race between two saves surfaces here as a validation error. */
	private fun save(cluster: Cluster): Cluster =
		try {
			repository.saveAndFlush(cluster)
		} catch (_: DataIntegrityViolationException) {
			throw ClusterValidationException(mapOf("name" to NAME_TAKEN))
		}

	private fun validate(input: ClusterInput, excludeId: Long?): ValidInput {
		val errors = linkedMapOf<String, String>()
		val name = input.name?.trim().orEmpty()
		val nameKey = name.lowercase(Locale.ROOT)
		when {
			name.isEmpty() -> errors["name"] = "Enter a name."
			name.length > NAME_MAX -> errors["name"] = "Use $NAME_MAX characters or fewer."
			isNameTaken(nameKey, excludeId) -> errors["name"] = NAME_TAKEN
		}
		val raw = input.address?.trim().orEmpty()
		val address = if (raw.isEmpty()) {
			errors["address"] = "Enter the controller's address."
			""
		} else {
			normalize(raw) ?: "".also { errors["address"] = "Use an http or https address like http://homelabs001:8088." }
		}
		if (address.length > ADDRESS_MAX) errors["address"] = "Use $ADDRESS_MAX characters or fewer."
		if (errors.isNotEmpty()) throw ClusterValidationException(errors)
		return ValidInput(name, nameKey, address)
	}

	private fun isNameTaken(nameKey: String, excludeId: Long?): Boolean =
		excludeId?.let { repository.existsByNameKeyAndIdNot(nameKey, it) } ?: repository.existsByNameKey(nameKey)

	/** The address without a trailing slash, or null when it isn't an `http(s)` URL with a host and no credentials. */
	private fun normalize(raw: String): String? {
		val uri = try {
			URI(raw)
		} catch (_: URISyntaxException) {
			return null
		}
		val scheme = uri.scheme?.lowercase(Locale.ROOT)
		if (scheme != "http" && scheme != "https") return null
		if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
		return raw.trimEnd('/')
	}

	private data class ValidInput(val name: String, val nameKey: String, val address: String)

	companion object {
		private const val NAME_MAX = 100
		private const val ADDRESS_MAX = 300
		private const val NAME_TAKEN = "A cluster with this name already exists."
	}
}
