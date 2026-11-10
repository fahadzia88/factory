package com.toptier.custom.feature

import com.toptier.custom.checkout.CheckoutTestConfig
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckout
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeInspector
import com.toptier.custom.feature.TaskFixtures.failingChecks
import com.toptier.custom.feature.TaskFixtures.pull
import com.toptier.custom.feature.TaskFixtures.task
import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.domain.FeatureTaskRepository
import com.toptier.custom.feature.exception.TaskNotFoundException
import com.toptier.custom.feature.service.FeatureService
import com.toptier.custom.feature.service.FeatureTasks
import com.toptier.custom.feature.service.WorkflowCodec
import com.toptier.custom.github.exception.GitHubTimeoutException
import com.toptier.custom.github.service.GitHubPullWriteClient
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
import com.toptier.custom.project.service.ProjectService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@Import(
	FeatureTasks::class, FeatureService::class, WorkflowCodec::class, ProjectService::class, WorktreeInspector::class,
	GitRunner::class, CheckoutTestConfig::class, FeatureTestConfig::class,
)
class FeatureTasksTests {

	@Autowired
	lateinit var service: FeatureTasks

	@Autowired
	lateinit var codec: WorkflowCodec

	@Autowired
	lateinit var projectRepository: ProjectRepository

	@Autowired
	lateinit var features: FeatureRepository

	@Autowired
	lateinit var tasks: FeatureTaskRepository

	@Autowired
	lateinit var checkouts: RepositoryCheckoutRepository

	@MockitoBean
	lateinit var pulls: GitHubPullWriteClient

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	lateinit var project: Project

	lateinit var feature: Feature

	@BeforeEach
	fun feature() {
		project = projectRepository.saveAndFlush(Project().apply {
			name = "Factory ${System.nanoTime()}"
			nameKey = name.lowercase()
			repositoryUrl = "https://github.com/acme/factory"
			createdAt = START
			updatedAt = START
		})
		checkouts.saveAndFlush(RepositoryCheckout().apply {
			projectId = project.id!!
			path = "/repos/${project.id}"
			state = CheckoutState.READY
			defaultBranch = "main"
			createdAt = START
		})
		feature = features.saveAndFlush(Feature().apply {
			projectId = project.id!!
			number = 6
			name = "Feature implementation"
			slug = "x"
			status = "implementation"
			workflow = codec.write(codec.read(FeatureFixtures.WORKFLOW))
			branch = "feat/006-x/00-docs"
			folder = "feature/006-x"
			createdAt = START
			updatedAt = START
		})
	}

	@Test
	fun `row 0 is created once, on the first read`() {
		val first = service.list(project.id!!, feature.id!!)
		val second = service.list(project.id!!, feature.id!!)

		assertEquals(listOf(0), first.map { it.task.number })
		assertEquals(listOf(0), second.map { it.task.number })
		val row = tasks.findByFeatureIdAndNumber(feature.id!!, 0)!!
		assertEquals("docs", row.layer)
		assertEquals("feat/006-x/00-docs", row.branch)
		assertEquals("main", row.baseBranch)
		assertNull(first[0].pull)
		assertNull(first[0].pullError)
		assertThrows<DataIntegrityViolationException> {
			tasks.saveAndFlush(task(0, "other", "main", featureId = feature.id!!))
		}
	}

	@Test
	fun `tasks list in order with their pull requests and failing checks`() {
		save(task(2, "feat/006-x/02-app-b", "feat/006-x/01-api-a", baseHead = "sha11", pullNumber = 12, featureId = feature.id!!))
		save(task(1, "feat/006-x/01-api-a", "feat/006-x/00-docs", pullNumber = 11, featureId = feature.id!!))
		given(pulls.status("acme", "factory", 11)).willReturn(pull(11))
		given(pulls.status("acme", "factory", 12)).willReturn(pull(12, checks = failingChecks()))

		val views = service.list(project.id!!, feature.id!!)

		assertEquals(listOf(0, 1, 2), views.map { it.task.number })
		assertEquals("test", views[2].pull!!.checks.runs.single().name)
		assertEquals("failure", views[2].pull!!.checks.state)
		assertFalse(views[2].needsRestack)
	}

	@Test
	fun `a moved base head means a restack`() {
		save(task(1, "feat/006-x/01-api-a", "feat/006-x/00-docs", pullNumber = 11, featureId = feature.id!!))
		save(task(2, "feat/006-x/02-app-b", "feat/006-x/01-api-a", baseHead = "old", pullNumber = 12, featureId = feature.id!!))
		given(pulls.status("acme", "factory", 11)).willReturn(pull(11, headSha = "new"))
		given(pulls.status("acme", "factory", 12)).willReturn(pull(12))

		assertTrue(service.list(project.id!!, feature.id!!)[2].needsRestack)
	}

	@Test
	fun `a GitHub failure on one task is its pullError and the others still load`() {
		save(task(1, "feat/006-x/01-api-a", "feat/006-x/00-docs", pullNumber = 11, featureId = feature.id!!))
		save(task(2, "feat/006-x/02-app-b", "feat/006-x/01-api-a", pullNumber = 12, featureId = feature.id!!))
		given(pulls.status("acme", "factory", 11)).willThrow(GitHubTimeoutException("x", SocketTimeoutException()))
		given(pulls.status("acme", "factory", 12)).willReturn(pull(12))

		val views = service.list(project.id!!, feature.id!!)

		assertNull(views[1].pull)
		assertEquals("GitHub didn't answer in time. Try again.", views[1].pullError!!.detail)
		assertEquals(12, views[2].pull!!.number)
		assertFalse(views[2].needsRestack)
		assertThrows<GitHubTimeoutException> { service.get(project.id!!, feature.id!!, 1) }
		assertEquals(12, service.get(project.id!!, feature.id!!, 2).pull!!.number)
	}

	@Test
	fun `an unknown task number is not found`() {
		assertThrows<TaskNotFoundException> { service.get(project.id!!, feature.id!!, 9) }
	}

	private fun save(task: com.toptier.custom.feature.domain.FeatureTask) = tasks.saveAndFlush(task)
}
