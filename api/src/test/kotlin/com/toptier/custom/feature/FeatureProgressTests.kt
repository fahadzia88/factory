package com.toptier.custom.feature

import com.toptier.custom.checkout.CheckoutTestConfig
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeInspector
import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.exception.InvalidStatusException
import com.toptier.custom.feature.exception.InvalidStepException
import com.toptier.custom.feature.exception.InvalidStepTypeException
import com.toptier.custom.feature.exception.StepNotFoundException
import com.toptier.custom.feature.service.FeatureProgress
import com.toptier.custom.feature.service.FeatureService
import com.toptier.custom.feature.service.StepChanges
import com.toptier.custom.feature.service.WorkflowCodec
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.support.MutableClock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DataJpaTest
@Import(
	FeatureProgress::class, FeatureService::class, WorkflowCodec::class, ProjectService::class, WorktreeInspector::class,
	GitRunner::class, CheckoutTestConfig::class, FeatureTestConfig::class,
)
class FeatureProgressTests {

	@Autowired
	lateinit var progress: FeatureProgress

	@Autowired
	lateinit var featureService: FeatureService

	@Autowired
	lateinit var codec: WorkflowCodec

	@Autowired
	lateinit var projectRepository: ProjectRepository

	@Autowired
	lateinit var features: FeatureRepository

	@Autowired
	lateinit var clock: MutableClock

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	lateinit var project: Project

	lateinit var feature: Feature

	@BeforeEach
	fun feature() {
		clock.now = START
		project = project("Project A")
		feature = feature(project)
		clock.advance(Duration.ofMinutes(1))
	}

	@Test
	fun `a listed status is set and moves updatedAt`() {
		val view = progress.setStatus(project.id!!, feature.id!!, "breakdown")

		assertEquals("breakdown", view.feature.status)
		assertEquals(clock.now, features.findById(feature.id!!).get().updatedAt)
	}

	@Test
	fun `a status the workflow doesn't list is refused and changes nothing`() {
		val ex = assertThrows<InvalidStatusException> { progress.setStatus(project.id!!, feature.id!!, "shipped") }

		assertEquals(STATES, ex.allowed)
		assertThrows<InvalidStatusException> { progress.setStatus(project.id!!, feature.id!!, null) }
		val row = features.findById(feature.id!!).get()
		assertEquals("requirements", row.status)
		assertEquals(START, row.updatedAt)
	}

	@Test
	fun `steps are added in order, in their type's first state, and a type can repeat`() {
		val (first, _) = progress.addStep(project.id!!, feature.id!!, "requirements", " Requirements ", "feature/004-features/requirements.md")
		val (second, _) = progress.addStep(project.id!!, feature.id!!, "spec", "Spec: 01-api-x", null)
		val (third, _) = progress.addStep(project.id!!, feature.id!!, "spec", "Spec: 02-app-y", "feature/004-features/tasks/02-app-y/spec.md")

		assertEquals(listOf(1, 2, 3), listOf(first.position, second.position, third.position))
		assertEquals("Requirements", first.title)
		assertEquals("draft", first.status)
		assertNull(second.documentPath)
		assertEquals(listOf("requirements", "spec", "spec"), featureService.get(project.id!!, feature.id!!).steps.map { it.typeKey })
		assertEquals(clock.now, features.findById(feature.id!!).get().updatedAt)
	}

	@Test
	fun `an unknown step type is refused with the keys`() {
		val ex = assertThrows<InvalidStepTypeException> { progress.addStep(project.id!!, feature.id!!, "plan", "Plan", null) }

		assertEquals(listOf("requirements", "breakdown", "spec"), ex.allowed)
	}

	@Test
	fun `bad titles and paths are refused`() {
		for (title in listOf("  ", "x".repeat(201))) {
			assertThrows<InvalidStepException> { progress.addStep(project.id!!, feature.id!!, "spec", title, null) }
		}
		for (path in listOf("/etc/passwd", "feature/other/x.md", "feature/004-features/../x.md", "feature/004-features", "feature/004-features//x.md")) {
			val ex = assertThrows<InvalidStepException> { progress.addStep(project.id!!, feature.id!!, "spec", "Spec", path) }
			assertEquals("documentPath must be a file inside feature/004-features/.", ex.message)
		}
		assertEquals(emptyList(), featureService.get(project.id!!, feature.id!!).steps)
	}

	@Test
	fun `a step's status, title and path change, and an empty path clears it`() {
		val (step, _) = progress.addStep(project.id!!, feature.id!!, "requirements", "Requirements", "feature/004-features/requirements.md")
		val stepId = step.id!!

		assertEquals("approved", progress.updateStep(project.id!!, feature.id!!, stepId, StepChanges(status = "approved")).first.status)
		val ex = assertThrows<InvalidStatusException> {
			progress.updateStep(project.id!!, feature.id!!, stepId, StepChanges(status = "done"))
		}
		assertEquals(listOf("draft", "approved"), ex.allowed)

		val (renamed, _) = progress.updateStep(
			project.id!!, feature.id!!, stepId, StepChanges(title = "Requirements v2", documentPath = "feature/004-features/req.md"),
		)
		assertEquals("Requirements v2", renamed.title)
		assertEquals("feature/004-features/req.md", renamed.documentPath)
		assertNull(progress.updateStep(project.id!!, feature.id!!, stepId, StepChanges(documentPath = "")).first.documentPath)
		assertThrows<InvalidStepException> { progress.updateStep(project.id!!, feature.id!!, stepId, StepChanges()) }
	}

	@Test
	fun `another feature's step is not found`() {
		val other = feature(project, number = 5)
		val (step, _) = progress.addStep(project.id!!, other.id!!, "spec", "Spec", null)

		assertThrows<StepNotFoundException> {
			progress.updateStep(project.id!!, feature.id!!, step.id!!, StepChanges(status = "approved"))
		}
	}

	@Test
	fun `an archived project's feature still records progress`() {
		project.archived = true
		projectRepository.saveAndFlush(project)

		assertEquals("done", progress.setStatus(project.id!!, feature.id!!, "done").feature.status)
	}

	private fun project(name: String): Project = projectRepository.saveAndFlush(Project().apply {
		this.name = name
		nameKey = name.lowercase()
		repositoryUrl = "https://github.com/acme/${name.hashCode()}"
		createdAt = START
		updatedAt = START
	})

	private fun feature(project: Project, number: Int = 4): Feature = features.saveAndFlush(Feature().apply {
		projectId = project.id!!
		this.number = number
		name = "Features"
		slug = "features"
		status = "requirements"
		workflow = codec.write(codec.read(FeatureFixtures.WORKFLOW))
		branch = "feat/%03d-features/00-docs".format(number)
		folder = "feature/%03d-features".format(number)
		createdAt = START
		updatedAt = START
	})

	private companion object {
		val STATES = listOf("requirements", "breakdown", "specs", "implementation", "done", "abandoned")
	}
}
