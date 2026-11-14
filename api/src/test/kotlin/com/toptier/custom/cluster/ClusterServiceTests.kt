package com.toptier.custom.cluster

import com.toptier.custom.cluster.exception.ClusterNotFoundException
import com.toptier.custom.cluster.exception.ClusterValidationException
import com.toptier.custom.cluster.service.ClusterDeletedEvent
import com.toptier.custom.cluster.service.ClusterInput
import com.toptier.custom.cluster.service.ClusterService
import com.toptier.custom.support.MutableClock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

@DataJpaTest
@Import(ClusterService::class, ClusterServiceTests.Config::class)
@RecordApplicationEvents
class ClusterServiceTests {

	@TestConfiguration
	class Config {
		@Bean
		fun clock() = MutableClock(START)
	}

	@Autowired
	lateinit var service: ClusterService

	@Autowired
	lateinit var clock: MutableClock

	@Autowired
	lateinit var events: ApplicationEvents

	@BeforeEach
	fun resetClock() {
		clock.now = START
	}

	@Test
	fun `creates a cluster with trimmed fields and no trailing slash`() {
		val cluster = service.create(ClusterInput("  Home lab  ", " http://homelabs001:8088/ "))

		assertEquals("Home lab", cluster.name)
		assertEquals("http://homelabs001:8088", cluster.address)
		assertEquals(START, cluster.createdAt)
		assertEquals(START, cluster.updatedAt)
	}

	@Test
	fun `accepts http and https addresses`() {
		service.create(ClusterInput("One", "http://controller.controller.svc:3000"))
		service.create(ClusterInput("Two", "HTTPS://controller.example.com"))

		assertEquals(2, service.list().size)
	}

	@Test
	fun `rejects a blank name and a blank address`() {
		val ex = assertThrows<ClusterValidationException> { service.create(ClusterInput(" ", "")) }

		assertEquals(
			mapOf("name" to "Enter a name.", "address" to "Enter the controller's address."),
			ex.errors,
		)
	}

	@Test
	fun `rejects a name over 100 characters`() {
		val ex = assertThrows<ClusterValidationException> {
			service.create(ClusterInput("n".repeat(101), "http://homelabs001:8088"))
		}

		assertEquals("Use 100 characters or fewer.", ex.errors["name"])
	}

	@Test
	fun `rejects addresses that are not http or https with a host and no credentials`() {
		for (address in listOf("ftp://homelabs001", "homelabs001:8088", "http://", "http://user:pw@homelabs001", "not a url")) {
			val ex = assertThrows<ClusterValidationException>(address) {
				service.create(ClusterInput("Cluster", address))
			}
			assertEquals("Use an http or https address like http://homelabs001:8088.", ex.errors["address"], address)
		}
	}

	@Test
	fun `rejects a duplicate name ignoring case`() {
		service.create(ClusterInput("Home lab", "http://a:1"))

		val ex = assertThrows<ClusterValidationException> { service.create(ClusterInput("HOME LAB", "http://b:1")) }

		assertEquals(mapOf("name" to "A cluster with this name already exists."), ex.errors)
	}

	@Test
	fun `updates name and address and keeps its own name`() {
		val cluster = service.create(ClusterInput("Home lab", "http://a:1"))
		clock.advance(Duration.ofMinutes(5))

		val updated = service.update(cluster.id!!, ClusterInput("HOME LAB", "https://b:2"))

		assertEquals("HOME LAB", updated.name)
		assertEquals("https://b:2", updated.address)
		assertEquals(START, updated.createdAt)
		assertEquals(START.plus(Duration.ofMinutes(5)), updated.updatedAt)
	}

	@Test
	fun `rejects renaming onto another cluster's name`() {
		service.create(ClusterInput("One", "http://a:1"))
		val two = service.create(ClusterInput("Two", "http://b:1"))

		assertThrows<ClusterValidationException> { service.update(two.id!!, ClusterInput("one", "http://b:1")) }
	}

	@Test
	fun `deletes a cluster and publishes the deleted event`() {
		val cluster = service.create(ClusterInput("Home lab", "http://a:1"))

		service.delete(cluster.id!!)

		assertEquals(0, service.list().size)
		assertEquals(listOf(ClusterDeletedEvent(cluster.id!!)), events.stream(ClusterDeletedEvent::class.java).toList())
	}

	@Test
	fun `reports unknown ids as not found`() {
		assertThrows<ClusterNotFoundException> { service.get(404) }
		assertThrows<ClusterNotFoundException> { service.update(404, ClusterInput("A", "http://a:1")) }
		assertThrows<ClusterNotFoundException> { service.delete(404) }
	}

	companion object {
		private val START: Instant = Instant.parse("2026-11-04T10:15:30Z")
	}
}
