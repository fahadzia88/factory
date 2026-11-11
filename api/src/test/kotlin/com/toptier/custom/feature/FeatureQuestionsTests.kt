package com.toptier.custom.feature

import com.toptier.custom.checkout.CheckoutTestConfig
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeInspector
import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureQuestionRepository
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.domain.QuestionStatus
import com.toptier.custom.feature.dto.OptionInput
import com.toptier.custom.feature.dto.QuestionInput
import com.toptier.custom.feature.dto.QuestionsRequest
import com.toptier.custom.feature.service.FeatureQuestions
import com.toptier.custom.feature.service.FeatureService
import com.toptier.custom.feature.service.WorkflowCodec
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
import com.toptier.custom.project.service.ProjectService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

@DataJpaTest
@Import(
	FeatureQuestions::class, FeatureService::class, WorkflowCodec::class, ProjectService::class, WorktreeInspector::class,
	GitRunner::class, CheckoutTestConfig::class, FeatureTestConfig::class,
)
class FeatureQuestionsTests {

	@Autowired
	lateinit var service: FeatureQuestions

	@Autowired
	lateinit var questions: FeatureQuestionRepository

	@Autowired
	lateinit var projectRepository: ProjectRepository

	@Autowired
	lateinit var features: FeatureRepository

	@Autowired
	lateinit var codec: WorkflowCodec

	@Autowired
	lateinit var jsonMapper: JsonMapper

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	lateinit var feature: Feature

	@BeforeEach
	fun feature() {
		val project = projectRepository.saveAndFlush(Project().apply {
			name = "Q ${System.nanoTime()}"
			nameKey = name.lowercase()
			repositoryUrl = "https://github.com/acme/${System.nanoTime()}"
			createdAt = START
			updatedAt = START
		})
		feature = features.saveAndFlush(Feature().apply {
			projectId = project.id!!
			number = 1
			name = "X"
			slug = "x"
			status = "requirements"
			workflow = codec.write(codec.read(FeatureFixtures.WORKFLOW))
			branch = "feat/001-x/00-docs"
			folder = "feature/001-x"
			createdAt = START
			updatedAt = START
		})
	}

	@Test
	fun `create stores the payload as the pending set`() {
		val created = service.create(feature.projectId, feature.id!!, request())

		val stored = questions.findById(created.id!!).get()
		assertEquals(QuestionStatus.PENDING, stored.status)
		val payload = jsonMapper.readTree(stored.payload)
		assertEquals("Token", payload.get("questions").get(0).get("header").asString())
		assertEquals("New token", payload.get("questions").get(0).get("options").get(1).get("label").asString())
	}

	@Test
	fun `a newer set supersedes the pending one, never an answered one`() {
		val answered = service.create(feature.projectId, feature.id!!, request()).apply { status = QuestionStatus.ANSWERED }
		questions.saveAndFlush(answered)
		val first = service.create(feature.projectId, feature.id!!, request())
		val second = service.create(feature.projectId, feature.id!!, request())

		assertEquals(
			listOf(QuestionStatus.ANSWERED, QuestionStatus.SUPERSEDED, QuestionStatus.PENDING),
			listOf(answered.id, first.id, second.id).map { questions.findById(it!!).get().status },
		)
	}

	private fun request() = QuestionsRequest(
		listOf(
			QuestionInput(
				"Token", "Which token?", false,
				listOf(OptionInput("Keep GIT_TOKEN", "Add Pull requests to it."), OptionInput("New token", "Only for pull requests.")),
			),
		),
	)
}
