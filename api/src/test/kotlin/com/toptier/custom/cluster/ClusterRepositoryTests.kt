package com.toptier.custom.cluster

import com.toptier.custom.cluster.domain.Cluster
import com.toptier.custom.cluster.domain.ClusterRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import kotlin.test.assertEquals

@DataJpaTest
class ClusterRepositoryTests {

	@Autowired
	lateinit var repository: ClusterRepository

	@Test
	fun `enforces unique lowercased names in the database`() {
		repository.saveAndFlush(cluster("Home lab", "http://homelabs001:8088"))

		assertThrows<DataIntegrityViolationException> {
			repository.saveAndFlush(cluster("HOME LAB", "http://other:8088"))
		}
	}

	@Test
	fun `lists clusters ordered by name`() {
		repository.saveAndFlush(cluster("beta", "http://b:1"))
		repository.saveAndFlush(cluster("Alpha", "http://a:1"))

		assertEquals(listOf("Alpha", "beta"), repository.findAllByOrderByNameKeyAscIdAsc().map { it.name })
	}

	private fun cluster(name: String, address: String) = Cluster().apply {
		this.name = name
		nameKey = name.lowercase()
		this.address = address
		createdAt = Instant.EPOCH
		updatedAt = Instant.EPOCH
	}
}
