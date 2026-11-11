package com.toptier.custom.feature

import com.toptier.custom.checkout.CheckoutTestConfig
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.ROOT
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.GitFixtures.fileUrl
import com.toptier.custom.checkout.GitFixtures.git
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.CheckoutPaths
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeInspector
import com.toptier.custom.checkout.service.WorktreeService
import com.toptier.custom.feature.TaskFixtures.pull
import com.toptier.custom.feature.config.FeatureProperties
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.domain.FeatureStepRepository
import com.toptier.custom.feature.domain.FeatureTaskRepository
import com.toptier.custom.feature.exception.InvalidStepTypeException
import com.toptier.custom.feature.exception.InvalidTaskException
import com.toptier.custom.feature.exception.PreviousTaskUnfinishedException
import com.toptier.custom.feature.exception.SpecsNotApprovedException
import com.toptier.custom.feature.exception.TaskExistsException
import com.toptier.custom.feature.exception.TaskOutOfOrderException
import com.toptier.custom.feature.service.FeatureCreator
import com.toptier.custom.feature.service.FeatureProgress
import com.toptier.custom.feature.service.FeatureService
import com.toptier.custom.feature.service.FeatureTasks
import com.toptier.custom.feature.service.FeatureView
import com.toptier.custom.feature.service.TaskStarter
import com.toptier.custom.feature.service.WorkflowCodec
import com.toptier.custom.feature.service.WorkflowReader
import com.toptier.custom.github.service.GitHubPullWriteClient
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
import com.toptier.custom.project.service.ProjectService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.util.FileSystemUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Starts tasks against `file://` bare remotes; GitHub's pull request reads are mocked. */
@DataJpaTest
@Import(
	TaskStarter::class, FeatureTasks::class, FeatureProgress::class, FeatureCreator::class, FeatureService::class,
	WorkflowReader::class, WorkflowCodec::class, CheckoutService::class, WorktreeService::class, WorktreeInspector::class,
	ProjectService::class, CheckoutLocks::class, GitRunner::class, CheckoutPaths::class, CheckoutTestConfig::class,
	FeatureTestConfig::class,
)
@EnableConfigurationProperties(FeatureProperties::class)
class TaskStartTests {

	@Autowired
	lateinit var starter: TaskStarter

	@Autowired
	lateinit var creator: FeatureCreator

	@Autowired
	lateinit var checkoutService: CheckoutService

	@Autowired
	lateinit var projectRepository: ProjectRepository

	@Autowired
	lateinit var features: FeatureRepository

	@Autowired
	lateinit var tasks: FeatureTaskRepository

	@Autowired
	lateinit var steps: FeatureStepRepository

	@Autowired
	lateinit var checkouts: RepositoryCheckoutRepository

	@Autowired
	lateinit var worktrees: RepositoryWorktreeRepository

	@MockitoSpyBean
	lateinit var progress: FeatureProgress

	@MockitoBean
	lateinit var pulls: GitHubPullWriteClient

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	@TempDir
	lateinit var tmp: Path

	@AfterEach
	fun emptyRoot() {
		ROOT.listDirectoryEntries().forEach { FileSystemUtils.deleteRecursively(it) }
	}

	@Test
	fun `an unapproved spec stops the start before anything is created`() {
		val (project, view) = featureWithSpecs(mapOf("01-api-a" to "approved", "02-app-b" to "draft"))

		val ex = assertThrows<SpecsNotApprovedException> { starter.start(project.id!!, view.feature.id!!, "01-api-a") }

		assertEquals(listOf("feature/001-x/tasks/02-app-b/spec.md"), ex.unapproved)
		assertNothingStarted(project, view)
	}

	@Test
	fun `the first task starts on the docs branch with its row, step and the feature status`() {
		val (project, view) = featureWithSpecs(mapOf("01-api-a" to "approved", "02-app-b" to "approved"))

		val started = starter.start(project.id!!, view.feature.id!!, "01-api-a")

		val task = started.task
		assertEquals("feat/001-x/01-api-a", task.branch)
		assertEquals("feat/001-x/00-docs", task.baseBranch)
		assertEquals("feature/001-x/tasks/01-api-a/spec.md", task.specPath)
		val clone = Path.of(checkouts.findByProjectId(project.id!!)!!.path)
		assertEquals(git(clone, "rev-parse", "origin/feat/001-x/00-docs"), task.baseHead)
		assertTrue(started.worktree != null && !started.worktree!!.missing)
		assertEquals(git(clone, "rev-parse", "origin/feat/001-x/00-docs"), git(Path.of(started.worktree!!.path), "rev-parse", "HEAD"))
		val step = steps.findById(task.stepId!!).get()
		assertEquals("implementation", step.typeKey)
		assertEquals("Task 01: api a", step.title)
		assertEquals("feature/001-x/tasks/01-api-a/spec.md", step.documentPath)
		assertEquals("implementation", features.findById(view.feature.id!!).get().status)
		assertEquals(listOf(0, 1), tasks.findByFeatureIdOrderByNumberAsc(view.feature.id!!).map { it.number })
	}

	@Test
	fun `tasks start in order, once, after the one below has a pull request`() {
		val (project, view) = featureWithSpecs(mapOf("01-api-a" to "approved", "02-app-b" to "approved", "03-agent-c" to "approved"))
		starter.start(project.id!!, view.feature.id!!, "01-api-a")

		assertThrows<PreviousTaskUnfinishedException> { starter.start(project.id!!, view.feature.id!!, "02-app-b") }
		assertEquals(2, assertThrows<TaskOutOfOrderException> { starter.start(project.id!!, view.feature.id!!, "03-agent-c") }.expected)
		assertThrows<TaskExistsException> { starter.start(project.id!!, view.feature.id!!, "01-api-a") }
		assertThrows<InvalidTaskException> { starter.start(project.id!!, view.feature.id!!, "02-db-b") }
		assertThrows<InvalidTaskException> { starter.start(project.id!!, view.feature.id!!, "04-api-missing") }
	}

	@Test
	fun `with the task below merged, the next task starts from the default branch`() {
		val (project, view) = featureWithSpecs(mapOf("01-api-a" to "approved", "02-app-b" to "approved"))
		starter.start(project.id!!, view.feature.id!!, "01-api-a")
		tasks.findByFeatureIdAndNumber(view.feature.id!!, 1)!!.let { it.pullNumber = 11; tasks.saveAndFlush(it) }
		given(pulls.status("acme", "factory", 10)).willReturn(pull(10, state = "merged"))
		given(pulls.status("acme", "factory", 11)).willReturn(pull(11, state = "merged"))

		assertEquals("main", starter.start(project.id!!, view.feature.id!!, "02-app-b").task.baseBranch)
	}

	@Test
	fun `a failure after the worktree was added undoes it`() {
		val (project, view) = featureWithSpecs(mapOf("01-api-a" to "approved"))
		willThrow(IllegalStateException("boom")).given(progress).addStep(eq(project.id!!), eq(view.feature.id!!), anyString(), anyString(), anyString())

		assertThrows<IllegalStateException> { starter.start(project.id!!, view.feature.id!!, "01-api-a") }

		assertNothingStarted(project, view)
	}

	@Test
	fun `a workflow without the implementation step creates nothing`() {
		val (project, view) = featureWithSpecs(mapOf("01-api-a" to "approved"), workflow = FeatureFixtures.WORKFLOW)

		assertThrows<InvalidStepTypeException> { starter.start(project.id!!, view.feature.id!!, "01-api-a") }

		assertNothingStarted(project, view)
	}

	/** No task row but row 0, no task worktree, no task branch in the clone. */
	private fun assertNothingStarted(project: Project, view: FeatureView) {
		assertEquals(listOf(0), tasks.findByFeatureIdOrderByNumberAsc(view.feature.id!!).map { it.number })
		val checkout = checkouts.findByProjectId(project.id!!)!!
		assertEquals(2, worktrees.findByCheckoutIdOrderByMainDescCreatedAtAscIdAsc(checkout.id!!).size)
		assertEquals("", git(Path.of(checkout.path), "branch", "--list", "feat/001-x/01-*"))
		assertFalse(steps.findByFeatureIdOrderByPositionAsc(view.feature.id!!).any { it.typeKey == "implementation" })
	}

	/** A feature "X" (001) with its docs PR open as #10, and task specs written in its docs worktree with the given statuses. */
	private fun featureWithSpecs(specs: Map<String, String>, workflow: String = WORKFLOW): Pair<Project, FeatureView> {
		val parent = Files.createTempDirectory(tmp, "remote")
		val remote = FeatureFixtures.seededRemote(
			parent,
			mapOf("feature/workflow.json" to workflow, ".claude/skills/feature-implementation/SKILL.md" to "# Implementation\n"),
		)
		val project = projectRepository.saveAndFlush(Project().apply {
			name = "Project ${remote.hashCode()}"
			nameKey = name.lowercase()
			repositoryUrl = fileUrl(remote)
			createdAt = START
			updatedAt = START
		})
		checkoutService.start(project.id!!)
		val view = creator.create(project.id!!, "X")
		// The clone keeps its file:// origin; pull request calls need a GitHub URL to parse
		project.repositoryUrl = "https://github.com/acme/factory"
		projectRepository.saveAndFlush(project)
		val docs = Path.of(view.worktree!!.path)
		for ((dir, status) in specs) {
			val spec = docs.resolve("feature/001-x/tasks/$dir/spec.md")
			Files.createDirectories(spec.parent)
			Files.writeString(spec, "---\nfeature: 001-x\nstatus: $status\n---\n# $dir\n")
		}
		docsPull(view.feature.id!!, 10)
		given(pulls.status("acme", "factory", 10)).willReturn(pull(10))
		return project to view
	}

	/** Gives row 0 its docs pull request, creating the row as the service would. */
	private fun docsPull(featureId: Long, number: Int) {
		val feature = features.findById(featureId).get()
		val row = tasks.findByFeatureIdAndNumber(featureId, 0)
			?: tasks.saveAndFlush(TaskFixtures.task(0, feature.branch, "main", featureId = featureId))
		row.pullNumber = number
		tasks.saveAndFlush(row)
	}

	private companion object {
		val WORKFLOW = FeatureFixtures.WORKFLOW.replace(
			"""{ "key": "spec", "title": "Task spec", "skill": "feature-task-spec", "states": ["draft", "approved"] }""",
			"""{ "key": "spec", "title": "Task spec", "skill": "feature-task-spec", "states": ["draft", "approved"] },
    { "key": "implementation", "title": "Implementation", "skill": "feature-implementation", "states": ["in-progress", "pr-open", "merged", "stopped", "skipped"] }""",
		)
	}
}
