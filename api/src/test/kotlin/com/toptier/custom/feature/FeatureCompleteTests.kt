package com.toptier.custom.feature

import com.toptier.custom.checkout.CheckoutTestConfig
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.ROOT
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.GitFixtures.fileUrl
import com.toptier.custom.checkout.GitFixtures.git
import com.toptier.custom.checkout.GitFixtures.scratchClone
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.checkout.exception.PushRejectedException
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.CheckoutPaths
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeInspector
import com.toptier.custom.checkout.service.WorktreeService
import com.toptier.custom.feature.TaskFixtures.pull
import com.toptier.custom.feature.config.FeatureProperties
import com.toptier.custom.feature.domain.FeatureTaskRepository
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.exception.InvalidStatusException
import com.toptier.custom.feature.exception.TasksUnfinishedException
import com.toptier.custom.feature.exception.UnfinishedTask
import com.toptier.custom.feature.service.FeatureCompleter
import com.toptier.custom.github.exception.GitHubTimeoutException
import java.net.SocketTimeoutException
import kotlin.test.assertTrue
import org.mockito.BDDMockito.willThrow
import com.toptier.custom.feature.service.FeatureCreator
import com.toptier.custom.feature.service.FeatureProgress
import com.toptier.custom.feature.service.FeatureService
import com.toptier.custom.feature.service.FeatureStarter
import com.toptier.custom.feature.service.FeatureTasks
import com.toptier.custom.feature.service.TaskPulls
import com.toptier.custom.feature.service.TaskStarter
import com.toptier.custom.feature.service.WorkflowCodec
import com.toptier.custom.feature.service.WorkflowReader
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
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willAnswer
import org.mockito.Mockito.never
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.util.FileSystemUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Completes a three-branch feature (docs, 01, 02) built against a `file://` bare remote; GitHub's PR reads are scripted. */
@DataJpaTest
@Import(
	FeatureCompleter::class, TaskPulls::class, FeatureStarter::class, TaskStarter::class, FeatureTasks::class,
	FeatureProgress::class, FeatureCreator::class, FeatureService::class, WorkflowReader::class, WorkflowCodec::class,
	CheckoutService::class, WorktreeService::class, WorktreeInspector::class, ProjectService::class, CheckoutLocks::class,
	GitRunner::class, CheckoutPaths::class, CheckoutTestConfig::class, FeatureTestConfig::class,
)
@EnableConfigurationProperties(FeatureProperties::class)
class FeatureCompleteTests {

	@Autowired
	lateinit var completer: FeatureCompleter

	@Autowired
	lateinit var features: FeatureRepository

	@Autowired
	lateinit var featureTasks: FeatureTasks

	@Autowired
	lateinit var featureStarter: FeatureStarter

	@Autowired
	lateinit var taskStarter: TaskStarter

	@Autowired
	lateinit var taskPulls: TaskPulls

	@Autowired
	lateinit var checkoutService: CheckoutService

	@Autowired
	lateinit var projectRepository: ProjectRepository

	@Autowired
	lateinit var tasks: FeatureTaskRepository

	@Autowired
	lateinit var worktrees: RepositoryWorktreeRepository

	@MockitoSpyBean
	lateinit var worktreeService: WorktreeService

	@MockitoBean
	lateinit var pulls: GitHubPullWriteClient

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	@TempDir
	lateinit var tmp: Path

	private var nextPull = 10
	lateinit var remote: Path
	lateinit var project: Project
	var featureId = 0L
	lateinit var one: Path
	lateinit var two: Path

	@BeforeEach
	fun stack() {
		given(pulls.create(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).willAnswer {
			val number = nextPull++
			GitHubPull(
				number, "PR $number", "open", false, null,
				GitHubRef(it.getArgument(3), "x", null, "sha"), GitHubRef(it.getArgument(4), "x", null),
				null, START, START, null, null, "https://github.com/acme/factory/pull/$number",
			)
		}
		val parent = Files.createTempDirectory(tmp, "remote")
		remote = FeatureFixtures.seededRemote(
			parent,
			mapOf("feature/workflow.json" to WORKFLOW, ".claude/skills/feature-implementation/SKILL.md" to "# Implementation\n"),
		)
		project = projectRepository.saveAndFlush(Project().apply {
			name = "Project ${remote.hashCode()}"
			nameKey = name.lowercase()
			repositoryUrl = fileUrl(remote)
			createdAt = START
			updatedAt = START
		})
		checkoutService.start(project.id!!)
		project.repositoryUrl = "https://github.com/acme/factory"
		projectRepository.saveAndFlush(project)
		val started = featureStarter.start(project.id!!, "X")
		featureId = started.view.feature.id!!
		val docs = Path.of(started.view.worktree!!.path)
		for (dir in listOf("01-api-a", "02-app-b")) {
			val spec = docs.resolve("feature/001-x/tasks/$dir/spec.md")
			Files.createDirectories(spec.parent)
			Files.writeString(spec, "---\nstatus: approved\n---\n# $dir\n")
		}
		script(10 to pull(10, headSha = head("feat/001-x/00-docs")))
		one = startAndPush("01-api-a", "a.txt", "one\n")
		taskPulls.open(project.id!!, featureId, 1, "A", listOf("AC-1"))
		script(11 to pull(11, headSha = head("feat/001-x/01-api-a")))
		two = startAndPush("02-app-b", "b.txt", "two\n")
		taskPulls.open(project.id!!, featureId, 2, "B", listOf("AC-2"))
		script(12 to pull(12, headSha = head("feat/001-x/02-app-b")))
	}

	@AfterEach
	fun emptyRoot() {
		ROOT.listDirectoryEntries().forEach { FileSystemUtils.deleteRecursively(it) }
	}

	@Test
	fun `with every pull request merged the feature is done and the task worktrees are removed`() {
		mergeAll()

		val completed = completer.complete(project.id!!, featureId)

		assertEquals("done", completed.view.feature.status)
		assertEquals(emptyList(), completed.kept)
		assertFalse(one.exists())
		assertFalse(two.exists())
		val branches = worktrees.findAll().map { it.branch }
		assertFalse("feat/001-x/01-api-a" in branches || "feat/001-x/02-app-b" in branches)
		assertTrue("feat/001-x/00-docs" in branches)
		assertEquals(listOf(0, 1, 2), tasks.findByFeatureIdOrderByNumberAsc(featureId).map { it.number })
	}

	@Test
	fun `an open pull request, a task without one, or a spec without a task keep the feature open`() {
		script(10 to pull(10, state = "merged"), 11 to pull(11, state = "merged"))
		tasks.findByFeatureIdAndNumber(featureId, 1)!!.let { it.pullNumber = null; tasks.saveAndFlush(it) }
		val docs = Path.of(worktrees.findAll().single { it.branch == "feat/001-x/00-docs" }.path)
		val spec = docs.resolve("feature/001-x/tasks/03-agent-c/spec.md")
		Files.createDirectories(spec.parent)
		Files.writeString(spec, "---\nstatus: approved\n---\n")

		val ex = assertThrows<TasksUnfinishedException> { completer.complete(project.id!!, featureId) }

		assertEquals(
			listOf(
				UnfinishedTask(1, "no pull request"),
				UnfinishedTask(2, "pull request not merged (open)"),
				UnfinishedTask(3, "no task started"),
			),
			ex.unfinished,
		)
		assertEquals("implementation", features.findById(featureId).get().status)
		assertTrue(one.exists() && two.exists())
	}

	@Test
	fun `a workflow without done changes nothing`() {
		mergeAll()
		features.findById(featureId).get().let {
			it.workflow = it.workflow.replace("\"done\",", "")
			features.saveAndFlush(it)
		}

		assertThrows<InvalidStatusException> { completer.complete(project.id!!, featureId) }

		assertTrue(one.exists() && two.exists())
	}

	@Test
	fun `a worktree that can't be removed is kept and named`() {
		mergeAll()
		val id = worktrees.findAll().single { it.branch == "feat/001-x/02-app-b" }.id!!
		willThrow(IllegalStateException("busy")).given(worktreeService).remove(project.id!!, id, false)

		val completed = completer.complete(project.id!!, featureId)

		assertEquals("done", completed.view.feature.status)
		assertEquals(listOf(com.toptier.custom.feature.service.KeptWorktree("feat/001-x/02-app-b", "busy")), completed.kept)
		assertFalse(one.exists())
		assertTrue(two.exists())
	}

	@Test
	fun `a GitHub failure changes nothing`() {
		mergeAll()
		given(pulls.status("acme", "factory", 12)).willThrow(GitHubTimeoutException("x", SocketTimeoutException()))

		assertThrows<GitHubTimeoutException> { completer.complete(project.id!!, featureId) }

		assertEquals("implementation", features.findById(featureId).get().status)
		assertTrue(one.exists() && two.exists())
	}

	private fun mergeAll() = script(10 to pull(10, state = "merged"), 11 to pull(11, state = "merged"), 12 to pull(12, state = "merged"))

	/** Scripts GitHub's answer for each pull request number. */
	private fun script(vararg statuses: Pair<Int, com.toptier.custom.github.service.PullStatus>) {
		for ((number, status) in statuses) given(pulls.status("acme", "factory", number)).willReturn(status)
	}

	private fun head(branch: String) = git(remote, "rev-parse", "refs/heads/$branch")

	private fun startAndPush(dir: String, file: String, content: String): Path {
		val started = taskStarter.start(project.id!!, featureId, dir)
		val worktree = Path.of(started.worktree!!.path)
		commitAndPush(worktree, file, content)
		return worktree
	}

	private fun commitAndPush(worktree: Path, file: String, content: String) {
		Files.writeString(worktree.resolve(file), content)
		git(worktree, "add", "-A")
		git(worktree, "commit", "-m", "Change $file")
		git(worktree, "push", "origin", "HEAD")
	}

	private companion object {
		val WORKFLOW = FeatureFixtures.WORKFLOW.replace(
			"""{ "key": "spec", "title": "Task spec", "skill": "feature-task-spec", "states": ["draft", "approved"] }""",
			"""{ "key": "spec", "title": "Task spec", "skill": "feature-task-spec", "states": ["draft", "approved"] },
    { "key": "implementation", "title": "Implementation", "skill": "feature-implementation", "states": ["in-progress", "pr-open", "merged", "stopped", "skipped"] }""",
		)
	}
}
