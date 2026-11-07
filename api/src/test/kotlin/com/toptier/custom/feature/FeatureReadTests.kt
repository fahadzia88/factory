package com.toptier.custom.feature

import com.toptier.custom.checkout.CheckoutTestConfig
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.ROOT
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckout
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktree
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeInspector
import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.domain.FeatureStep
import com.toptier.custom.feature.domain.FeatureStepRepository
import com.toptier.custom.feature.exception.FeatureNotFoundException
import com.toptier.custom.feature.service.FeatureService
import com.toptier.custom.feature.service.FeatureWorktree
import com.toptier.custom.feature.service.Workflow
import com.toptier.custom.feature.service.WorkflowCodec
import com.toptier.custom.feature.service.WorkflowStep
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.project.service.ProjectService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.util.FileSystemUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DataJpaTest
@Import(
	FeatureService::class, WorkflowCodec::class, ProjectService::class, WorktreeInspector::class, GitRunner::class,
	CheckoutTestConfig::class, FeatureTestConfig::class,
)
class FeatureReadTests {

	@Autowired
	lateinit var service: FeatureService

	@Autowired
	lateinit var codec: WorkflowCodec

	@Autowired
	lateinit var projectRepository: ProjectRepository

	@Autowired
	lateinit var features: FeatureRepository

	@Autowired
	lateinit var steps: FeatureStepRepository

	@Autowired
	lateinit var checkouts: RepositoryCheckoutRepository

	@Autowired
	lateinit var worktrees: RepositoryWorktreeRepository

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	lateinit var project: Project

	@BeforeEach
	fun project() {
		project = project("Project A")
	}

	@AfterEach
	fun emptyRoot() {
		ROOT.listDirectoryEntries().forEach { FileSystemUtils.deleteRecursively(it) }
	}

	@Test
	fun `list returns the project's features newest number first`() {
		feature(project, 1)
		feature(project, 3)
		feature(project("Project B"), 2)

		assertEquals(listOf(3, 1), service.list(project.id!!).map { it.number })
	}

	@Test
	fun `get returns steps in position order with the stored workflow`() {
		val feature = feature(project, 4)
		step(feature, 2, "spec", "Spec: 01-api-x")
		step(feature, 1, "requirements", "Requirements")

		val view = service.get(project.id!!, feature.id!!)

		assertEquals(WORKFLOW, view.workflow)
		assertEquals(listOf("Requirements", "Spec: 01-api-x"), view.steps.map { it.title })
	}

	@Test
	fun `the worktree of the feature's branch is found, missing or absent`() {
		val feature = feature(project, 4)
		val checkout = checkouts.saveAndFlush(RepositoryCheckout().apply {
			projectId = project.id!!
			path = ROOT.resolve(project.id.toString()).toString()
			state = CheckoutState.READY
			createdAt = START
		})
		val dir = ROOT.resolve("${project.id}.worktrees/feat__004-feature-4__00-docs")
		Files.createDirectories(dir.resolve(".git"))
		val row = worktrees.saveAndFlush(RepositoryWorktree().apply {
			checkoutId = checkout.id!!
			branch = feature.branch
			path = dir.toString()
			createdAt = START
		})

		assertEquals(FeatureWorktree(row.id!!, dir.toString(), false), service.get(project.id!!, feature.id!!).worktree)

		FileSystemUtils.deleteRecursively(dir)
		assertEquals(true, service.get(project.id!!, feature.id!!).worktree?.missing)

		checkout.state = CheckoutState.FAILED
		checkouts.saveAndFlush(checkout)
		assertNull(service.get(project.id!!, feature.id!!).worktree)

		checkout.state = CheckoutState.READY
		checkouts.saveAndFlush(checkout)
		worktrees.delete(row)
		worktrees.flush()
		assertNull(service.get(project.id!!, feature.id!!).worktree)
	}

	@Test
	fun `a feature without a checkout has no worktree`() {
		val feature = feature(project, 1)

		assertNull(service.get(project.id!!, feature.id!!).worktree)
	}

	@Test
	fun `another project's feature, an unknown id and an unknown project are not found`() {
		val other = feature(project("Project B"), 1)

		assertThrows<FeatureNotFoundException> { service.get(project.id!!, other.id!!) }
		assertThrows<FeatureNotFoundException> { service.get(project.id!!, 999_999) }
		assertThrows<ProjectNotFoundException> { service.get(999_999, other.id!!) }
		assertThrows<ProjectNotFoundException> { service.list(999_999) }
	}

	private fun project(name: String): Project = projectRepository.saveAndFlush(Project().apply {
		this.name = name
		nameKey = name.lowercase()
		repositoryUrl = "https://github.com/acme/${name.lowercase().replace(' ', '-')}"
		createdAt = START
		updatedAt = START
	})

	private fun feature(project: Project, number: Int): Feature = features.saveAndFlush(Feature().apply {
		projectId = project.id!!
		this.number = number
		name = "Feature $number"
		slug = "feature-$number"
		status = WORKFLOW.initialState
		workflow = codec.write(WORKFLOW)
		branch = "feat/%03d-feature-$number/00-docs".format(number)
		folder = "feature/%03d-feature-$number".format(number)
		createdAt = START
		updatedAt = START
	})

	private fun step(feature: Feature, position: Int, type: String, title: String) = steps.saveAndFlush(FeatureStep().apply {
		featureId = feature.id!!
		this.position = position
		typeKey = type
		this.title = title
		status = "draft"
		createdAt = START
		updatedAt = START
	})

	private companion object {
		val WORKFLOW = Workflow(
			listOf("requirements", "done"),
			listOf(
				WorkflowStep("requirements", "Requirements", "feature-requirements", listOf("draft", "approved")),
				WorkflowStep("spec", "Task spec", "feature-task-spec", listOf("draft", "approved")),
			),
		)
	}
}
