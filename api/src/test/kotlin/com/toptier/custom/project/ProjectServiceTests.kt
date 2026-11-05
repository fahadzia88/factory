package com.toptier.custom.project

import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.github.service.RepositoryCheck
import com.toptier.custom.project.domain.ProjectStatus
import com.toptier.custom.project.exception.ProjectArchivedException
import com.toptier.custom.project.exception.ProjectConflictException
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.project.exception.ProjectValidationException
import com.toptier.custom.project.service.ProjectInput
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.support.MutableClock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mockito.never
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@Import(ProjectService::class, ProjectServiceTests.Config::class)
class ProjectServiceTests {

	@TestConfiguration
	class Config {
		@Bean
		fun clock() = MutableClock(START)
	}

	@Autowired
	lateinit var service: ProjectService

	@Autowired
	lateinit var clock: MutableClock

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	@BeforeEach
	fun foundByDefault() {
		clock.now = START
		given(checker.check(anyString(), anyString())).willReturn(RepositoryCheck.FOUND)
	}

	@Test
	fun `creates a project as planning, verified, with timestamps from the clock`() {
		val project = service.create(input(description = "  Builds the apps.  ", status = null))

		assertEquals("Factory", project.name)
		assertEquals("https://github.com/acme/factory", project.repositoryUrl)
		assertEquals("Builds the apps.", project.description)
		assertEquals(ProjectStatus.PLANNING, project.status)
		assertTrue(project.repositoryVerified)
		assertFalse(project.archived)
		assertEquals(START, project.createdAt)
		assertEquals(START, project.updatedAt)
		then(checker).should().check("acme", "factory")
	}

	@Test
	fun `stores a blank description as null and trims the name and URL`() {
		val project = service.create(input(name = "  Factory  ", url = " https://github.com/acme/factory ", description = "   "))

		assertEquals("Factory", project.name)
		assertEquals("https://github.com/acme/factory", project.repositoryUrl)
		assertNull(project.description)
	}

	@Test
	fun `reports every invalid field at once`() {
		val ex = assertThrows<ProjectValidationException> {
			service.create(ProjectInput(name = " ", repositoryUrl = "", description = "x".repeat(2001), status = "paused"))
		}

		assertEquals(
			mapOf(
				"name" to "Enter a name.",
				"repositoryUrl" to "Enter the repository URL.",
				"description" to "Use 2,000 characters or fewer.",
				"status" to "Choose a status.",
			),
			ex.errors,
		)
		then(checker).shouldHaveNoInteractions()
	}

	@Test
	fun `rejects a name over 100 characters`() {
		val ex = assertThrows<ProjectValidationException> { service.create(input(name = "n".repeat(101))) }

		assertEquals("Use 100 characters or fewer.", ex.errors["name"])
	}

	@Test
	fun `accepts only github owner and repo URLs`() {
		for (url in listOf(
			"https://gitlab.com/acme/factory",
			"https://github.com/acme",
			"https://github.com/acme/factory/tree/main",
			"http://github.com/acme/factory",
			"https://github.com/acme/factory?tab=readme",
		)) {
			val ex = assertThrows<ProjectValidationException>(url) { service.create(input(url = url)) }
			assertEquals("Use a GitHub URL like https://github.com/owner/repo.", ex.errors["repositoryUrl"], url)
		}
	}

	@Test
	fun `checks the repository without its git suffix`() {
		service.create(input(url = "https://github.com/acme/factory.git/"))

		then(checker).should().check("acme", "factory")
	}

	@Test
	fun `rejects a duplicate name ignoring case, archived projects included`() {
		val first = service.create(input(name = "Factory"))
		service.archive(first.id!!)

		val ex = assertThrows<ProjectConflictException> {
			service.create(input(name = "factory", url = "https://github.com/acme/other"))
		}

		assertEquals(mapOf("name" to "A project with this name already exists."), ex.errors)
	}

	@Test
	fun `rejects an exactly duplicated repository URL but not a differently written one`() {
		service.create(input(name = "Factory", url = "https://github.com/acme/app"))

		val ex = assertThrows<ProjectConflictException> {
			service.create(input(name = "Other", url = "https://github.com/acme/app"))
		}
		assertEquals(mapOf("repositoryUrl" to "Another project already uses this repository."), ex.errors)

		val variant = service.create(input(name = "Variant", url = "https://github.com/acme/App.git"))
		assertEquals("https://github.com/acme/App.git", variant.repositoryUrl)
	}

	@Test
	fun `rejects a repository GitHub can't find`() {
		given(checker.check("acme", "missing")).willReturn(RepositoryCheck.NOT_FOUND)

		val ex = assertThrows<ProjectValidationException> { service.create(input(url = "https://github.com/acme/missing")) }

		assertEquals(mapOf("repositoryUrl" to "Repository not found on GitHub."), ex.errors)
	}

	@Test
	fun `saves as unverified when GitHub can't be asked`() {
		given(checker.check("acme", "factory")).willReturn(RepositoryCheck.UNAVAILABLE)

		val project = service.create(input())

		assertFalse(project.repositoryVerified)
	}

	@Test
	fun `re-checks an unverified project on every save`() {
		given(checker.check("acme", "factory")).willReturn(RepositoryCheck.UNAVAILABLE)
		val project = service.create(input())

		given(checker.check("acme", "factory")).willReturn(RepositoryCheck.FOUND)
		val updated = service.update(project.id!!, input(status = "active"))
		assertTrue(updated.repositoryVerified)

	}

	@Test
	fun `rejects an unverified project's save when the re-check finds no repository`() {
		given(checker.check("acme", "factory")).willReturn(RepositoryCheck.UNAVAILABLE)
		val project = service.create(input())

		given(checker.check("acme", "factory")).willReturn(RepositoryCheck.NOT_FOUND)
		val ex = assertThrows<ProjectValidationException> { service.update(project.id!!, input(status = "active")) }

		assertEquals(mapOf("repositoryUrl" to "Repository not found on GitHub."), ex.errors)
	}

	@Test
	fun `updates fields and the updated time without asking GitHub for an unchanged verified URL`() {
		val project = service.create(input())
		clock.advance(Duration.ofMinutes(5))

		val updated = service.update(project.id!!, input(name = "Factory 2", description = "New", status = "maintenance"))

		assertEquals("Factory 2", updated.name)
		assertEquals("New", updated.description)
		assertEquals(ProjectStatus.MAINTENANCE, updated.status)
		assertEquals(ProjectStatus.MAINTENANCE.ordinal, updated.statusOrder)
		assertEquals(START, updated.createdAt)
		assertEquals(START.plus(Duration.ofMinutes(5)), updated.updatedAt)
		then(checker).should().check("acme", "factory") // only the create
	}

	@Test
	fun `checks a changed URL on update`() {
		val project = service.create(input())

		service.update(project.id!!, input(url = "https://github.com/acme/renamed", status = "active"))

		then(checker).should().check("acme", "renamed")
	}

	@Test
	fun `keeps its own name and URL on update`() {
		val project = service.create(input())

		val updated = service.update(project.id!!, input(name = "FACTORY", status = "active"))

		assertEquals("FACTORY", updated.name)
	}

	@Test
	fun `requires a status on update`() {
		val project = service.create(input())

		val ex = assertThrows<ProjectValidationException> { service.update(project.id!!, input(status = null)) }

		assertEquals(mapOf("status" to "Choose a status."), ex.errors)
	}

	@Test
	fun `archives without changing the status, idempotently`() {
		val project = service.create(input(status = "active"))
		clock.advance(Duration.ofMinutes(1))

		val archived = service.archive(project.id!!)
		assertTrue(archived.archived)
		assertEquals(ProjectStatus.ACTIVE, archived.status)
		val archivedAt = archived.updatedAt

		clock.advance(Duration.ofMinutes(1))
		assertEquals(archivedAt, service.archive(project.id!!).updatedAt)
	}

	@Test
	fun `refuses to edit an archived project`() {
		val project = service.create(input())
		service.archive(project.id!!)

		assertThrows<ProjectArchivedException> { service.update(project.id!!, input(name = "Renamed", status = "active")) }
		then(checker).should(never()).check("acme", "renamed")
	}

	@Test
	fun `restores with the status it had`() {
		val project = service.create(input(status = "retired"))
		service.archive(project.id!!)

		val restored = service.restore(project.id!!)

		assertFalse(restored.archived)
		assertEquals(ProjectStatus.RETIRED, restored.status)
		service.update(project.id!!, input(name = "Editable again", status = "retired"))
	}

	@Test
	fun `reports unknown ids as not found`() {
		assertThrows<ProjectNotFoundException> { service.get(404) }
		assertThrows<ProjectNotFoundException> { service.update(404, input(status = "active")) }
		assertThrows<ProjectNotFoundException> { service.archive(404) }
		assertThrows<ProjectNotFoundException> { service.restore(404) }
	}

	private fun input(
		name: String = "Factory",
		url: String = "https://github.com/acme/factory",
		description: String? = null,
		status: String? = "planning",
	) = ProjectInput(name, url, description, status)

	companion object {
		private val START: Instant = Instant.parse("2026-11-04T10:15:30Z")
	}
}
