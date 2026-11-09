package com.toptier.custom.feature

import com.toptier.custom.feature.controller.FeatureExceptionHandler
import com.toptier.custom.feature.controller.FeatureFilesController
import com.toptier.custom.feature.exception.FeatureFileNotFoundException
import com.toptier.custom.feature.exception.FeatureNotFoundException
import com.toptier.custom.feature.exception.FileTooLargeException
import com.toptier.custom.feature.exception.InvalidFilePathException
import com.toptier.custom.feature.exception.UnsupportedFileTypeException
import com.toptier.custom.feature.exception.WorktreeMissingException
import com.toptier.custom.feature.service.FeatureFileContent
import com.toptier.custom.feature.service.FeatureFileNode
import com.toptier.custom.feature.service.FeatureFileTree
import com.toptier.custom.feature.service.FeatureFiles
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.security.config.SecurityConfig
import com.toptier.custom.security.config.SecurityProperties
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Instant

@WebMvcTest(FeatureFilesController::class)
@Import(SecurityConfig::class, FeatureExceptionHandler::class)
@EnableConfigurationProperties(SecurityProperties::class)
@TestPropertySource(properties = ["app.security.api-token=test-token"])
class FeatureFilesControllerTests {

	@Autowired
	lateinit var mockMvc: MockMvc

	@MockitoBean
	lateinit var featureFiles: FeatureFiles

	@Test
	fun `both routes answer 401 without a token`() {
		mockMvc.get(FILES).andExpect { status { isUnauthorized() } }
		mockMvc.get("$CONTENT?path=requirements.md").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `tree answers the nested folder`() {
		val spec = FeatureFileNode("spec.md", "tasks/01-api-x/spec.md", directory = false, markdown = true, children = emptyList())
		val task = FeatureFileNode("01-api-x", "tasks/01-api-x", directory = true, markdown = false, children = listOf(spec))
		val tasks = FeatureFileNode("tasks", "tasks", directory = true, markdown = false, children = listOf(task))
		val empty = FeatureFileNode("notes", "notes", directory = true, markdown = false, children = emptyList())
		val requirements = FeatureFileNode("requirements.md", "requirements.md", directory = false, markdown = true, children = emptyList())
		val diagram = FeatureFileNode("diagram.png", "diagram.png", directory = false, markdown = false, children = emptyList())
		given(featureFiles.tree(12, 21)).willReturn(FeatureFileTree("feature/005-probe", listOf(tasks, empty, requirements, diagram)))

		mockMvc.get(FILES) { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.root") { value("feature/005-probe") }
			jsonPath("$.entries.length()") { value(4) }
			jsonPath("$.entries[0].name") { value("tasks") }
			jsonPath("$.entries[0].path") { value("tasks") }
			jsonPath("$.entries[0].type") { value("directory") }
			jsonPath("$.entries[0].markdown") { doesNotExist() }
			jsonPath("$.entries[0].children[0].type") { value("directory") }
			jsonPath("$.entries[0].children[0].children[0].name") { value("spec.md") }
			jsonPath("$.entries[0].children[0].children[0].path") { value("tasks/01-api-x/spec.md") }
			jsonPath("$.entries[0].children[0].children[0].type") { value("file") }
			jsonPath("$.entries[0].children[0].children[0].markdown") { value(true) }
			jsonPath("$.entries[0].children[0].children[0].children") { doesNotExist() }
			jsonPath("$.entries[1].type") { value("directory") }
			jsonPath("$.entries[1].children.length()") { value(0) }
			jsonPath("$.entries[2].type") { value("file") }
			jsonPath("$.entries[2].markdown") { value(true) }
			jsonPath("$.entries[2].children") { doesNotExist() }
			jsonPath("$.entries[3].name") { value("diagram.png") }
			jsonPath("$.entries[3].markdown") { value(false) }
		}
	}

	@Test
	fun `an empty tree answers no entries`() {
		given(featureFiles.tree(12, 21)).willReturn(FeatureFileTree("feature/005-probe", emptyList()))

		mockMvc.get(FILES) { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.root") { value("feature/005-probe") }
			jsonPath("$.entries.length()") { value(0) }
		}
	}

	@Test
	fun `content answers the file`() {
		given(featureFiles.read(12, 21, "requirements.md"))
			.willReturn(FeatureFileContent("requirements.md", "# Probe\n", 8, AT))

		mockMvc.get("$CONTENT?path=requirements.md") { auth() }.andExpect {
			status { isOk() }
			jsonPath("$.path") { value("requirements.md") }
			jsonPath("$.content") { value("# Probe\n") }
			jsonPath("$.size") { value(8) }
			jsonPath("$.updatedAt") { value("2026-11-08T04:32:15Z") }
		}
	}

	@Test
	fun `a missing worktree answers 409 on both routes`() {
		val missing = WorktreeMissingException("feat/005-probe")
		given(featureFiles.tree(12, 21)).willThrow(missing)
		given(featureFiles.read(12, 21, "requirements.md")).willThrow(missing)

		mockMvc.get(FILES) { auth() }.andExpect { problem(409, "worktree_missing", missing.message) }
		mockMvc.get("$CONTENT?path=requirements.md") { auth() }
			.andExpect { problem(409, "worktree_missing", missing.message) }
	}

	@Test
	fun `content maps the file errors`() {
		given(featureFiles.read(12, 21, "../rules.md")).willThrow(InvalidFilePathException())
		given(featureFiles.read(12, 21, "gone.md")).willThrow(FeatureFileNotFoundException())
		given(featureFiles.read(12, 21, "diagram.png")).willThrow(UnsupportedFileTypeException())
		given(featureFiles.read(12, 21, "big.md")).willThrow(FileTooLargeException())

		mockMvc.get(CONTENT) { auth(); param("path", "../rules.md") }
			.andExpect { problem(400, "invalid_path", InvalidFilePathException().message) }
		mockMvc.get(CONTENT) { auth(); param("path", "gone.md") }
			.andExpect { problem(404, "file_not_found", FeatureFileNotFoundException().message) }
		mockMvc.get(CONTENT) { auth(); param("path", "diagram.png") }
			.andExpect { problem(415, "unsupported_file_type", UnsupportedFileTypeException().message) }
		mockMvc.get(CONTENT) { auth(); param("path", "big.md") }
			.andExpect { problem(413, "file_too_large", FileTooLargeException().message) }
	}

	@Test
	fun `a missing feature or project answers 404`() {
		given(featureFiles.tree(12, 21)).willThrow(FeatureNotFoundException())
		given(featureFiles.read(12, 21, "requirements.md")).willThrow(FeatureNotFoundException())
		given(featureFiles.tree(13, 21)).willThrow(ProjectNotFoundException(13))

		mockMvc.get(FILES) { auth() }.andExpect { problem(404, "feature_not_found") }
		mockMvc.get("$CONTENT?path=requirements.md") { auth() }.andExpect { problem(404, "feature_not_found") }
		mockMvc.get("/api/projects/13/features/21/files") { auth() }.andExpect { problem(404, "project_not_found") }
	}

	@Test
	fun `bad requests answer 400 invalid_request`() {
		mockMvc.get("/api/projects/12/features/abc/files") { auth() }.andExpect { problem(400, "invalid_request") }
		mockMvc.get(CONTENT) { auth() }.andExpect { problem(400, "invalid_request") }
	}

	@Test
	fun `both routes are read-only`() {
		for (url in listOf(FILES, CONTENT)) {
			mockMvc.post(url) { auth() }.andExpect { status { isMethodNotAllowed() } }
			mockMvc.put(url) { auth() }.andExpect { status { isMethodNotAllowed() } }
			mockMvc.patch(url) { auth() }.andExpect { status { isMethodNotAllowed() } }
			mockMvc.delete(url) { auth() }.andExpect { status { isMethodNotAllowed() } }
		}
	}

	private fun MockMvcResultMatchersDsl.problem(status: Int, code: String, detail: String? = null) {
		status { isEqualTo(status) }
		jsonPath("$.code") { value(code) }
		if (detail != null) jsonPath("$.detail") { value(detail) }
	}

	private fun MockHttpServletRequestDsl.auth() {
		header("Authorization", "Bearer test-token")
	}

	companion object {
		const val FILES = "/api/projects/12/features/21/files"
		const val CONTENT = "$FILES/content"
		val AT: Instant = Instant.parse("2026-11-08T04:32:15Z")
	}
}
