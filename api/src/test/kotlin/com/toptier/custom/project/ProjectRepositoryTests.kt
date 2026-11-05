package com.toptier.custom.project

import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant

@DataJpaTest
class ProjectRepositoryTests {

	@Autowired
	lateinit var repository: ProjectRepository

	@Test
	fun `enforces unique lowercased names in the database`() {
		repository.saveAndFlush(project("Factory", "https://github.com/acme/one"))

		assertThrows<DataIntegrityViolationException> {
			repository.saveAndFlush(project("FACTORY", "https://github.com/acme/two"))
		}
	}

	@Test
	fun `enforces unique repository URLs in the database`() {
		repository.saveAndFlush(project("One", "https://github.com/acme/app"))

		assertThrows<DataIntegrityViolationException> {
			repository.saveAndFlush(project("Two", "https://github.com/acme/app"))
		}
	}

	private fun project(name: String, url: String) = Project().apply {
		this.name = name
		nameKey = name.lowercase()
		repositoryUrl = url
		createdAt = Instant.EPOCH
		updatedAt = Instant.EPOCH
	}
}
