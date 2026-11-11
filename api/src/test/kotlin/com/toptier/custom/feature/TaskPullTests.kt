package com.toptier.custom.feature

import com.toptier.custom.checkout.CheckoutTestConfig
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.ROOT
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.GitFixtures.fileUrl
import com.toptier.custom.checkout.GitFixtures.git
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
import com.toptier.custom.feature.exception.InvalidFeatureRequestException
import com.toptier.custom.feature.exception.PullExistsException
import com.toptier.custom.feature.exception.TaskNotPushedException
import com.toptier.custom.feature.service.FeatureCreator
import com.toptier.custom.feature.service.FeatureProgress
import com.toptier.custom.feature.service.FeatureService
import com.toptier.custom.feature.service.FeatureStarter
import com.toptier.custom.feature.service.FeatureTasks
import com.toptier.custom.feature.service.FeatureView
import com.toptier.custom.feature.service.TaskPulls
import com.toptier.custom.feature.service.TaskStarter
import com.toptier.custom.feature.service.WorkflowCodec
import com.toptier.custom.feature.service.WorkflowReader
import com.toptier.custom.github.exception.GitHubRefusedException
import com.toptier.custom.github.exception.GitHubUnreachableException
import com.toptier.custom.github.service.GitHubPull
import com.toptier.custom.github.service.GitHubPullWriteClient
import com.toptier.custom.github.service.GitHubRef
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
import com.toptier.custom.project.service.ProjectService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.never
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.util.FileSystemUtils
import java.net.ConnectException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opens task and docs pull requests against `file://` bare remotes; GitHub is mocked. */
@DataJpaTest
@Import(
	TaskPulls::class, FeatureStarter::class, TaskStarter::class, FeatureTasks::class, FeatureProgress::class,
	FeatureCreator::class, FeatureService::class, WorkflowReader::class, WorkflowCodec::class, CheckoutService::class,
	WorktreeService::class, WorktreeInspector::class, ProjectService::class, CheckoutLocks::class, GitRunner::class,
	CheckoutPaths::class, CheckoutTestConfig::class, FeatureTestConfig::class,
)
@EnableConfigurationProperties(FeatureProperties::class)
class TaskPullTests {

	@Autowired
	lateinit var taskPulls: TaskPulls

	@Autowired
	lateinit var featureStarter: FeatureStarter

	@Autowired
	lateinit var taskStarter: TaskStarter

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

	@MockitoBean
	lateinit var pulls: GitHubPullWriteClient

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	@TempDir
	lateinit var tmp: Path

	private var nextPull = 10
	private val created = mutableListOf<Created>()

	data class Created(val title: String, val head: String, val base: String, val body: String)

	@BeforeEach
	fun github() {
		given(pulls.create(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).willAnswer {
			val number = nextPull++
			created += Created(it.getArgument(2), it.getArgument(3), it.getArgument(4), it.getArgument(5))
			given(pulls.status("acme", "factory", number)).willReturn(pull(number))
			gitHubPull(number, it.getArgument(3), it.getArgument(4))
		}
	}

	@AfterEach
	fun emptyRoot() {
		ROOT.listDirectoryEntries().forEach { FileSystemUtils.deleteRecursively(it) }
	}

	@Test
	fun `creating a feature opens its docs pull request`() {
		val (_, view) = feature()

		val docs = created.single()
		assertEquals("001 X: docs", docs.title)
		assertEquals("feat/001-x/00-docs", docs.head)
		assertEquals("main", docs.base)
		assertTrue("https://github.com/acme/factory/blob/feat/001-x/00-docs/feature/001-x" in docs.body, docs.body)
		assertEquals(10, tasks.findByFeatureIdAndNumber(view.feature.id!!, 0)!!.pullNumber)
	}

	@Test
	fun `a failed docs pull request leaves the feature with the error`() {
		willThrow(GitHubUnreachableException("x", ConnectException()))
			.given(pulls).create(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())

		val (project, started) = feature(expectDocsPull = false)

		assertEquals("GitHub couldn't be reached. Try again.", started.second)
		assertEquals(1, features.findByProjectIdOrderByNumberDesc(project.id!!).size)
		assertNull(tasks.findByFeatureIdAndNumber(started.first.feature.id!!, 0)?.pullNumber)
	}

	@Test
	fun `a task's pull request goes into the docs branch with its spec and ACs`() {
		val (project, view) = feature()
		val task = startAndPush(project, view, "01-api-a")

		val opened = taskPulls.open(project.id!!, view.feature.id!!, 1, " Task records ", listOf("AC-3", "AC-4"))

		val pr = created.last()
		assertEquals("001 01 api: Task records", pr.title)
		assertEquals(task, pr.head)
		assertEquals("feat/001-x/00-docs", pr.base)
		assertTrue("Spec: https://github.com/acme/factory/blob/feat/001-x/00-docs/feature/001-x/tasks/01-api-a/spec.md" in pr.body, pr.body)
		assertTrue("ACs covered: AC-3, AC-4" in pr.body, pr.body)
		assertTrue("Stacked on #10" in pr.body, pr.body)
		assertEquals(11, opened.task.pullNumber)
		assertEquals("pr-open", steps.findById(opened.task.stepId!!).get().status)
	}

	@Test
	fun `the next task stacks on an open one, and on the default branch once everything below merged`() {
		val (project, view) = feature()
		startAndPush(project, view, "01-api-a")
		taskPulls.open(project.id!!, view.feature.id!!, 1, "A", listOf("AC-1"))
		val second = startAndPush(project, view, "02-app-b")

		taskPulls.open(project.id!!, view.feature.id!!, 2, "B", listOf("AC-2"))
		assertEquals("feat/001-x/01-api-a", created.last().base)
		assertTrue("Stacked on #11" in created.last().body)

		tasks.findByFeatureIdAndNumber(view.feature.id!!, 2)!!.let { it.pullNumber = null; tasks.saveAndFlush(it) }
		given(pulls.status("acme", "factory", 10)).willReturn(pull(10, state = "merged"))
		given(pulls.status("acme", "factory", 11)).willReturn(pull(11, state = "merged"))
		taskPulls.open(project.id!!, view.feature.id!!, 2, "B", listOf("AC-2"))
		assertEquals("main", created.last().base)
		assertEquals(second, created.last().head)
	}

	@Test
	fun `refusals open nothing`() {
		val (project, view) = feature()
		startAndPush(project, view, "01-api-a", push = false)
		val before = created.size

		assertThrows<TaskNotPushedException> { taskPulls.open(project.id!!, view.feature.id!!, 1, "A", listOf("AC-1")) }
		for ((task, acs) in listOf("  " to listOf("AC-1"), "A" to emptyList(), "A" to listOf("ac-1"), "A" to listOf("AC-"))) {
			assertThrows<InvalidFeatureRequestException> { taskPulls.open(project.id!!, view.feature.id!!, 1, task, acs) }
		}
		assertThrows<PullExistsException> { taskPulls.openDocs(project.id!!, view.feature.id!!) }
		assertEquals(before, created.size)
	}

	@Test
	fun `a GitHub refusal propagates and saves no pull number`() {
		val (project, view) = feature()
		startAndPush(project, view, "01-api-a")
		willThrow(GitHubRefusedException(403, "x"))
			.given(pulls).create(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())

		assertThrows<GitHubRefusedException> { taskPulls.open(project.id!!, view.feature.id!!, 1, "A", listOf("AC-1")) }

		assertNull(tasks.findByFeatureIdAndNumber(view.feature.id!!, 1)!!.pullNumber)
		then(pulls).should(never()).setBase(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString())
	}

	/** Starts task [dir] and pushes its branch to the remote (as the agent's commit tool would), returning the branch. */
	private fun startAndPush(project: Project, view: FeatureView, dir: String, push: Boolean = true): String {
		val started = taskStarter.start(project.id!!, view.feature.id!!, dir)
		val worktree = Path.of(started.worktree!!.path)
		if (push) {
			Files.writeString(worktree.resolve("$dir.txt"), "work\n")
			git(worktree, "add", "-A")
			git(worktree, "commit", "-m", "work")
			git(worktree, "push", "origin", started.task.branch)
		}
		return started.task.branch
	}

	private fun feature(): Pair<Project, FeatureView> {
		val (project, started) = feature(expectDocsPull = true)
		return project to started.first
	}

	/** A project whose clone has the implementation workflow, and feature "X" (001) started with task specs approved. */
	private fun feature(expectDocsPull: Boolean): Pair<Project, Pair<FeatureView, String?>> {
		val parent = Files.createTempDirectory(tmp, "remote")
		val workflow = FeatureFixtures.WORKFLOW.replace(
			"""{ "key": "spec", "title": "Task spec", "skill": "feature-task-spec", "states": ["draft", "approved"] }""",
			"""{ "key": "spec", "title": "Task spec", "skill": "feature-task-spec", "states": ["draft", "approved"] },
    { "key": "implementation", "title": "Implementation", "skill": "feature-implementation", "states": ["in-progress", "pr-open", "merged", "stopped", "skipped"] }""",
		)
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
		// The clone keeps its file:// origin; pull request calls need a GitHub URL to parse
		project.repositoryUrl = "https://github.com/acme/factory"
		projectRepository.saveAndFlush(project)
		val started = featureStarter.start(project.id!!, "X")
		if (expectDocsPull) assertNull(started.docsPullError)
		val docs = Path.of(started.view.worktree!!.path)
		for (dir in listOf("01-api-a", "02-app-b")) {
			val spec = docs.resolve("feature/001-x/tasks/$dir/spec.md")
			Files.createDirectories(spec.parent)
			Files.writeString(spec, "---\nstatus: approved\n---\n# $dir\n")
		}
		return project to (started.view to started.docsPullError)
	}

	private fun gitHubPull(number: Int, head: String, base: String) = GitHubPull(
		number = number,
		title = "PR $number",
		state = "open",
		user = null,
		head = GitHubRef(head, "acme:$head", null, "sha$number"),
		base = GitHubRef(base, "acme:$base", null),
		body = null,
		createdAt = START,
		updatedAt = START,
		mergedAt = null,
		closedAt = null,
		htmlUrl = "https://github.com/acme/factory/pull/$number",
	)
}
