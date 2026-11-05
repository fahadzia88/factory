package com.toptier.custom.project

import com.toptier.custom.project.controller.ProjectController
import com.toptier.custom.project.controller.ProjectExceptionHandler
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectStatus
import com.toptier.custom.project.exception.ProjectArchivedException
import com.toptier.custom.project.exception.ProjectConflictException
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.project.exception.ProjectValidationException
import com.toptier.custom.project.service.ProjectInput
import com.toptier.custom.project.service.ProjectListQuery
import com.toptier.custom.project.service.ProjectSummary
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.security.config.SecurityConfig
import com.toptier.custom.security.config.SecurityProperties
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Instant

@WebMvcTest(ProjectController::class)
@Import(SecurityConfig::class, ProjectExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class ProjectControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@MockitoBean
	lateinit var projectService: ProjectService

	@Test
	fun `rejects requests without a token`() {
		mockMvc.get("/api/projects/1").andExpect { status { isUnauthorized() } }
		mockMvc.post("/api/projects") { json(BODY) }.andExpect { status { isUnauthorized() } }
		mockMvc.put("/api/projects/1") { json(BODY) }.andExpect { status { isUnauthorized() } }
		mockMvc.post("/api/projects/1/archive").andExpect { status { isUnauthorized() } }
		mockMvc.post("/api/projects/1/restore").andExpect { status { isUnauthorized() } }
		mockMvc.get("/api/projects").andExpect { status { isUnauthorized() } }
		mockMvc.get("/api/projects/summary").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `lists projects with the query passed through`() {
		val query = ProjectListQuery.of("fac", "active", true, "name", "desc", 2, 10)
		given(projectService.list(query)).willReturn(PageImpl(listOf(project()), PageRequest.of(1, 10), 11))

		mockMvc.get("/api/projects?q=fac&status=active&includeArchived=true&sort=name&direction=desc&page=2&size=10") {
			auth()
		}.andExpect {
			status { isOk() }
			jsonPath("$.items[0].id") { value(42) }
			jsonPath("$.page") { value(2) }
			jsonPath("$.size") { value(10) }
			jsonPath("$.totalItems") { value(11) }
			jsonPath("$.totalPages") { value(2) }
		}
	}

	@Test
	fun `rejects an unknown sort`() {
		mockMvc.get("/api/projects?sort=id") { auth() }.andExpect {
			status { isBadRequest() }
			jsonPath("$.errors.sort") { value("Unknown value.") }
		}
	}

	@Test
	fun `rejects a parameter of the wrong type as a problem detail`() {
		mockMvc.get("/api/projects?includeArchived=maybe") { auth() }.andExpect {
			status { isBadRequest() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.detail") { value("Invalid value for includeArchived.") }
		}
	}

	@Test
	fun `summarizes for the Dashboard`() {
		given(projectService.summary()).willReturn(
			ProjectSummary(ProjectStatus.entries.associateWithTo(linkedMapOf()) { if (it == ProjectStatus.ACTIVE) 5L else 0L }, listOf(project())),
		)

		mockMvc.get("/api/projects/summary") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.counts.planning") { value(0) }
			jsonPath("$.counts.active") { value(5) }
			jsonPath("$.counts.maintenance") { value(0) }
			jsonPath("$.counts.retired") { value(0) }
			jsonPath("$.recent[0].name") { value("Factory") }
		}
	}

	@Test
	fun `creates a project`() {
		given(projectService.create(ProjectInput("Factory", "https://github.com/acme/factory", "Builds the apps.", null)))
			.willReturn(project())

		mockMvc.post("/api/projects") {
			auth()
			json("""{"name":"Factory","repositoryUrl":"https://github.com/acme/factory","description":"Builds the apps."}""")
		}.andExpect {
			status { isCreated() }
			header { string("Location", "/api/projects/42") }
			jsonPath("$.id") { value(42) }
			jsonPath("$.name") { value("Factory") }
			jsonPath("$.repositoryUrl") { value("https://github.com/acme/factory") }
			jsonPath("$.description") { value("Builds the apps.") }
			jsonPath("$.status") { value("planning") }
			jsonPath("$.archived") { value(false) }
			jsonPath("$.repositoryVerified") { value(true) }
			jsonPath("$.createdAt") { value("2026-11-04T10:15:30Z") }
			jsonPath("$.updatedAt") { value("2026-11-04T10:15:30Z") }
		}
	}

	@Test
	fun `returns field errors for invalid input`() {
		given(projectService.create(ProjectInput(null, null, null, null)))
			.willThrow(ProjectValidationException(mapOf("name" to "Enter a name.", "repositoryUrl" to "Enter the repository URL.")))

		mockMvc.post("/api/projects") { auth(); json("{}") }.andExpect {
			status { isBadRequest() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.detail") { value("The project couldn't be saved.") }
			jsonPath("$.errors.name") { value("Enter a name.") }
			jsonPath("$.errors.repositoryUrl") { value("Enter the repository URL.") }
		}
	}

	@Test
	fun `returns 409 with field errors for a duplicate`() {
		given(projectService.create(ProjectInput("Factory", "https://github.com/acme/factory", null, null)))
			.willThrow(ProjectConflictException(mapOf("repositoryUrl" to "Another project already uses this repository.")))

		mockMvc.post("/api/projects") { auth(); json(BODY) }.andExpect {
			status { isConflict() }
			jsonPath("$.errors.repositoryUrl") { value("Another project already uses this repository.") }
		}
	}

	@Test
	fun `updates a project`() {
		given(projectService.update(42, ProjectInput("Factory", "https://github.com/acme/factory", null, "active")))
			.willReturn(project(status = ProjectStatus.ACTIVE))

		mockMvc.put("/api/projects/42") {
			auth()
			json("""{"name":"Factory","repositoryUrl":"https://github.com/acme/factory","status":"active"}""")
		}.andExpect {
			status { isOk() }
			jsonPath("$.status") { value("active") }
		}
	}

	@Test
	fun `refuses to edit an archived project without field errors`() {
		given(projectService.update(42, ProjectInput("Factory", "https://github.com/acme/factory", null, null)))
			.willThrow(ProjectArchivedException(42))

		mockMvc.put("/api/projects/42") { auth(); json(BODY) }.andExpect {
			status { isConflict() }
			jsonPath("$.detail") { value("Archived projects can't be edited. Restore it first.") }
			jsonPath("$.errors") { doesNotExist() }
		}
	}

	@Test
	fun `archives and restores`() {
		given(projectService.archive(42)).willReturn(project(archived = true))
		given(projectService.restore(42)).willReturn(project(archived = false))

		mockMvc.post("/api/projects/42/archive") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.archived") { value(true) }
		}
		mockMvc.post("/api/projects/42/restore") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.archived") { value(false) }
		}
	}

	@Test
	fun `answers 404 for an unknown project`() {
		given(projectService.get(404)).willThrow(ProjectNotFoundException(404))

		mockMvc.get("/api/projects/404") { auth() }.andExpect {
			status { isNotFound() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.detail") { value("Project not found.") }
		}
	}

	@Test
	fun `has no delete`() {
		mockMvc.delete("/api/projects/42") { auth() }.andExpect { status { isMethodNotAllowed() } }
	}

	@Test
	fun `rejects malformed JSON`() {
		mockMvc.post("/api/projects") { auth(); json("{not json") }.andExpect {
			status { isBadRequest() }
			jsonPath("$.detail") { value("The request body isn't valid JSON.") }
		}
	}

	private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.auth() {
		header("Authorization", "Bearer test-token")
	}

	private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.json(body: String) {
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun project(status: ProjectStatus = ProjectStatus.PLANNING, archived: Boolean = false) = Project().apply {
		id = 42
		name = "Factory"
		nameKey = "factory"
		repositoryUrl = "https://github.com/acme/factory"
		description = "Builds the apps."
		this.status = status
		statusOrder = status.ordinal
		this.archived = archived
		repositoryVerified = true
		createdAt = NOW
		updatedAt = NOW
	}

	companion object {
		private val NOW: Instant = Instant.parse("2026-11-04T10:15:30Z")
		private const val BODY = """{"name":"Factory","repositoryUrl":"https://github.com/acme/factory"}"""
	}
}
