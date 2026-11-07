package com.toptier.custom.feature

import com.toptier.custom.checkout.CheckoutTestConfig
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.ROOT
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.GitFixtures.fileUrl
import com.toptier.custom.checkout.GitFixtures.git
import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.checkout.exception.GitRemoteException
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.CheckoutPaths
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeInspector
import com.toptier.custom.checkout.service.WorktreeService
import com.toptier.custom.feature.config.FeatureProperties
import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.exception.FeatureProjectArchivedException
import com.toptier.custom.feature.exception.InvalidFeatureNameException
import com.toptier.custom.feature.exception.WorkflowInvalidException
import com.toptier.custom.feature.service.FeatureCreator
import com.toptier.custom.feature.service.FeatureService
import com.toptier.custom.feature.service.WorkflowCodec
import com.toptier.custom.feature.service.WorkflowReader
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
import com.toptier.custom.project.service.ProjectService
import com.toptier.custom.support.MutableClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.BDDMockito.willThrow
import org.mockito.ArgumentMatchers.any
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.util.FileSystemUtils
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Creates features against `file://` bare remotes standing in for GitHub. */
@DataJpaTest
@Import(
	FeatureCreator::class, FeatureService::class, WorkflowReader::class, WorkflowCodec::class,
	CheckoutService::class, WorktreeService::class, WorktreeInspector::class, ProjectService::class, CheckoutLocks::class,
	GitRunner::class, CheckoutPaths::class, CheckoutTestConfig::class, FeatureTestConfig::class,
)
@EnableConfigurationProperties(FeatureProperties::class)
class FeatureCreateTests {

	@Autowired
	lateinit var creator: FeatureCreator

	@Autowired
	lateinit var checkoutService: CheckoutService

	@Autowired
	lateinit var codec: WorkflowCodec

	@Autowired
	lateinit var projectRepository: ProjectRepository

	@MockitoSpyBean
	lateinit var features: FeatureRepository

	@Autowired
	lateinit var checkouts: RepositoryCheckoutRepository

	@Autowired
	lateinit var worktrees: RepositoryWorktreeRepository

	@Autowired
	lateinit var clock: MutableClock

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	@TempDir
	lateinit var tmp: Path

	@AfterEach
	fun emptyRoot() {
		ROOT.listDirectoryEntries().forEach { FileSystemUtils.deleteRecursively(it) }
	}

	@Test
	fun `creates the feature with its worktree, requirements file, commit and push`() {
		val (project, remote) = readyProject()

		val view = creator.create(project.id!!, "  Feature records ")

		val feature = view.feature
		assertEquals(1, feature.number)
		assertEquals("Feature records", feature.name)
		assertEquals("feature-records", feature.slug)
		assertEquals("requirements", feature.status)
		assertEquals("feat/001-feature-records/00-docs", feature.branch)
		assertEquals("feature/001-feature-records", feature.folder)
		assertEquals(codec.read(feature.workflow), view.workflow)
		assertEquals(listOf("requirements", "breakdown", "spec"), view.workflow.steps.map { it.key })
		assertEquals(START, feature.createdAt)

		val worktree = view.worktree!!
		assertFalse(worktree.missing)
		val dir = Path.of(worktree.path)
		val requirements = Files.readString(dir.resolve("feature/001-feature-records/requirements.md"))
		assertTrue("feature: 001-feature-records" in requirements, requirements)
		assertTrue("# Feature records" in requirements, requirements)
		assertTrue("- ${LocalDate.ofInstant(START, ZoneOffset.UTC)} — Created." in requirements, requirements)
		assertEquals(
			"factory <factory@users.noreply.github.com>|factory <factory@users.noreply.github.com>|001-feature-records: start feature",
			git(dir, "log", "-1", "--format=%an <%ae>|%cn <%ce>|%s"),
		)
		assertEquals(git(dir, "rev-parse", "HEAD"), git(remote, "rev-parse", "refs/heads/feat/001-feature-records/00-docs"))
		assertEquals("", git(dir, "status", "--porcelain"))
	}

	@Test
	fun `numbers follow records and folders on the default branch`() {
		val (project, _) = readyProject(files = mapOf("feature/006-x/requirements.md" to "# X\n"))
		for (n in 1..4) features.saveAndFlush(feature(project, n))

		assertEquals(7, creator.create(project.id!!, "Next").feature.number)
		assertEquals(8, creator.create(project.id!!, "After").feature.number)
	}

	@Test
	fun `workflow problems create nothing`() {
		assertNothingCreated(skip = setOf("feature/workflow.json"), message = "feature/workflow.json: the file is missing.")
		assertNothingCreated(files = mapOf("feature/workflow.json" to "{ nope"), message = "feature/workflow.json: isn't valid JSON")
		assertNothingCreated(
			skip = setOf(".claude/skills/feature-breakdown/SKILL.md"),
			message = "feature/workflow.json: steps[1].skill \"feature-breakdown\" has no .claude/skills/feature-breakdown/SKILL.md.",
		)
		assertNothingCreated(skip = setOf("feature/_templates/requirements.md"), message = "feature/_templates/requirements.md: the file is missing.")
	}

	@Test
	fun `bad names create nothing`() {
		val (project, remote) = readyProject()

		for (name in listOf("", "x".repeat(101), "!!!")) {
			assertThrows<InvalidFeatureNameException> { creator.create(project.id!!, name) }
		}
		assertClean(project, remote)
	}

	@Test
	fun `an archived project or a checkout that isn't ready is refused`() {
		val (project, _) = readyProject()
		val unready = project("No checkout")
		val failed = project("Failed checkout")
		checkouts.saveAndFlush(com.toptier.custom.checkout.domain.RepositoryCheckout().apply {
			projectId = failed.id!!
			path = ROOT.resolve(failed.id.toString()).toString()
			state = CheckoutState.FAILED
			createdAt = START
		})
		project.archived = true
		projectRepository.saveAndFlush(project)

		assertThrows<FeatureProjectArchivedException> { creator.create(project.id!!, "X") }
		assertThrows<CheckoutNotReadyException> { creator.create(unready.id!!, "X") }
		assertThrows<CheckoutNotReadyException> { creator.create(failed.id!!, "X") }
	}

	@Test
	fun `a rejected push removes the worktree and branch and saves nothing`() {
		val (project, remote) = readyProject()
		val hook = remote.resolve("hooks/pre-receive")
		Files.writeString(hook, "#!/bin/sh\nexit 1\n")
		hook.toFile().setExecutable(true)

		assertThrows<GitRemoteException> { creator.create(project.id!!, "Feature records") }

		assertClean(project, remote)
		assertFalse(Files.exists(ROOT.resolve("${project.id}.worktrees/feat__001-feature-records__00-docs")))
	}

	@Test
	fun `a failed save after the push deletes the branch on GitHub too`() {
		val (project, remote) = readyProject()
		willThrow(DataIntegrityViolationException("duplicate")).given(features).saveAndFlush(any(Feature::class.java))

		assertThrows<DataIntegrityViolationException> { creator.create(project.id!!, "Feature records") }

		assertClean(project, remote)
	}

	private fun assertNothingCreated(files: Map<String, String> = emptyMap(), skip: Set<String> = emptySet(), message: String) {
		val (project, remote) = readyProject(files, skip)

		val ex = assertThrows<WorkflowInvalidException> { creator.create(project.id!!, "Feature records") }

		assertTrue(ex.message!!.startsWith(message), ex.message)
		assertClean(project, remote)
	}

	/** No feature row, no worktree row besides the main one, and no feature branch in the clone or on the remote. */
	private fun assertClean(project: Project, remote: Path) {
		assertEquals(emptyList(), features.findByProjectIdOrderByNumberDesc(project.id!!))
		val checkout = checkouts.findByProjectId(project.id!!)!!
		assertEquals(listOf(true), worktrees.findByCheckoutIdOrderByMainDescCreatedAtAscIdAsc(checkout.id!!).map { it.main })
		assertEquals("", git(Path.of(checkout.path), "branch", "--list", "feat/*"))
		assertEquals("", git(remote, "branch", "--list", "feat/*"))
	}

	private fun readyProject(files: Map<String, String> = emptyMap(), skip: Set<String> = emptySet()): Pair<Project, Path> {
		clock.now = START
		val parent = Files.createTempDirectory(tmp, "remote")
		val remote = FeatureFixtures.seededRemote(parent, files, skip)
		val project = project("Project ${remote.hashCode()}", fileUrl(remote))
		val view = checkoutService.start(project.id!!)
		assertEquals(CheckoutState.READY, checkouts.findById(view.checkout.id!!).get().state)
		return project to remote
	}

	private fun project(name: String, url: String = "https://github.com/acme/${name.hashCode()}"): Project =
		projectRepository.saveAndFlush(Project().apply {
			this.name = name
			nameKey = name.lowercase()
			repositoryUrl = url
			createdAt = START
			updatedAt = START
		})

	private fun feature(project: Project, number: Int) = Feature().apply {
		projectId = project.id!!
		this.number = number
		name = "Old $number"
		slug = "old-$number"
		status = "done"
		workflow = "{}"
		branch = "feat/%03d-old-$number/00-docs".format(number)
		folder = "feature/%03d-old-$number".format(number)
		createdAt = START
		updatedAt = START
	}
}
