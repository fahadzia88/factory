package com.toptier.custom.project

import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.github.service.RepositoryCheck
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectStatus
import com.toptier.custom.project.exception.ProjectValidationException
import com.toptier.custom.project.service.ProjectInput
import com.toptier.custom.project.service.ProjectListQuery
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.support.MutableClock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DataJpaTest
@Import(ProjectService::class, ProjectListTests.Config::class)
class ProjectListTests {

	@TestConfiguration
	class Config {
		@Bean
		fun clock() = MutableClock(Instant.parse("2026-11-01T00:00:00Z"))
	}

	@Autowired
	lateinit var service: ProjectService

	@Autowired
	lateinit var clock: MutableClock

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	private var seq = 0

	@BeforeEach
	fun found() {
		given(checker.check(anyString(), anyString())).willReturn(RepositoryCheck.FOUND)
	}

	@Test
	fun `pages non-archived projects 20 at a time, newest updated first`() {
		val created = (1..25).map { create("Project $it") }

		val first = service.list(ProjectListQuery.of())
		assertEquals(20, first.content.size)
		assertEquals(25, first.totalElements)
		assertEquals(2, first.totalPages)
		assertEquals(created.reversed().take(20).map { it.id }, first.content.map { it.id })

		val second = service.list(ProjectListQuery.of(page = 2))
		assertEquals(created.take(5).reversed().map { it.id }, second.content.map { it.id })

		val past = service.list(ProjectListQuery.of(page = 3))
		assertTrue(past.content.isEmpty())
		assertEquals(25, past.totalElements)
	}

	@Test
	fun `searches names ignoring case, across pages, with wildcards taken literally`() {
		create("Factory")
		repeat(25) { create("Filler $it") }
		create("Artifacts FACTORY")
		create("Billing")
		create("100% coverage")
		create("100 percent")

		assertEquals(setOf("Factory", "Artifacts FACTORY"), names(ProjectListQuery.of(q = " fac ", size = 100)))
		assertEquals(2, service.list(ProjectListQuery.of(q = "factory", size = 1)).totalElements)
		assertEquals(setOf("100% coverage"), names(ProjectListQuery.of(q = "%")))
		assertEquals(emptySet(), names(ProjectListQuery.of(q = "_illing")))
	}

	@Test
	fun `filters by status`() {
		create("A", status = "active")
		create("B", status = "planning")
		create("C", status = "active")

		assertEquals(setOf("A", "C"), names(ProjectListQuery.of(status = "active")))
		assertEquals(setOf("A", "B", "C"), names(ProjectListQuery.of()))
	}

	@Test
	fun `hides archived projects unless asked`() {
		create("Live")
		service.archive(create("Old").id!!)

		assertEquals(setOf("Live"), names(ProjectListQuery.of()))
		assertEquals(setOf("Live", "Old"), names(ProjectListQuery.of(includeArchived = true)))
	}

	@Test
	fun `sorts by name ignoring case, by status in lifecycle order, and by last updated`() {
		val b = create("beta", status = "retired")
		val a = create("Alpha", status = "maintenance")
		val c = create("Charlie", status = "planning")
		val d = create("delta", status = "active")

		assertEquals(listOf("Alpha", "beta", "Charlie", "delta"), ordered(ProjectListQuery.of(sort = "name")))
		assertEquals(listOf("delta", "Charlie", "beta", "Alpha"), ordered(ProjectListQuery.of(sort = "name", direction = "desc")))
		assertEquals(listOf("Charlie", "delta", "Alpha", "beta"), ordered(ProjectListQuery.of(sort = "status")))
		assertEquals(listOf("beta", "Alpha", "delta", "Charlie"), ordered(ProjectListQuery.of(sort = "status", direction = "desc")))
		assertEquals(listOf(b, a, c, d).map { it.name }, ordered(ProjectListQuery.of(sort = "updatedAt", direction = "asc")))
		assertEquals(listOf(d, c, a, b).map { it.name }, ordered(ProjectListQuery.of()))
	}

	@Test
	fun `breaks ties by id`() {
		val first = create("Same time 1", advance = false)
		val second = create("Same time 2", advance = false)

		assertEquals(listOf(second.id, first.id), service.list(ProjectListQuery.of(q = "same time")).content.map { it.id })
	}

	@Test
	fun `rejects bad query parameters`() {
		val ex = assertThrows<ProjectValidationException> {
			ProjectListQuery.of(status = "paused", sort = "id", direction = "up", page = 0, size = 101)
		}

		assertEquals(setOf("status", "sort", "direction", "page", "size"), ex.errors.keys)
	}

	@Test
	fun `summarizes non-archived projects for the Dashboard`() {
		repeat(3) { create("Active $it", status = "active") }
		create("Planned", status = "planning")
		val newest = (1..5).map { create("Retired $it", status = "retired") }
		service.archive(create("Archived", status = "maintenance").id!!)

		val summary = service.summary()

		assertEquals(
			listOf(ProjectStatus.PLANNING to 1L, ProjectStatus.ACTIVE to 3L, ProjectStatus.MAINTENANCE to 0L, ProjectStatus.RETIRED to 5L),
			summary.counts.toList(),
		)
		assertEquals(newest.reversed().map { it.id }, summary.recent.map { it.id })
	}

	private fun create(name: String, status: String = "planning", advance: Boolean = true): Project {
		if (advance) clock.advance(Duration.ofMinutes(1))
		return service.create(ProjectInput(name, "https://github.com/acme/repo-${seq++}", null, status))
	}

	private fun names(query: ProjectListQuery) = service.list(query).content.map { it.name }.toSet()

	private fun ordered(query: ProjectListQuery) = service.list(query).content.map { it.name }
}
