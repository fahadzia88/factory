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
import com.toptier.custom.feature.exception.FeatureWorktreeMissingException
import com.toptier.custom.feature.exception.RestackConflictException
import com.toptier.custom.feature.exception.RestackNotNeededException
import com.toptier.custom.feature.exception.TaskClosedException
import com.toptier.custom.feature.exception.TaskDirtyException
import com.toptier.custom.feature.service.FeatureCreator
import com.toptier.custom.feature.service.FeatureProgress
import com.toptier.custom.feature.service.FeatureService
import com.toptier.custom.feature.service.FeatureStarter
import com.toptier.custom.feature.service.FeatureTasks
import com.toptier.custom.feature.service.TaskPulls
import com.toptier.custom.feature.service.TaskRestacker
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

/** Restacks a three-branch stack (docs, 01, 02) against a `file://` bare remote; GitHub's PR reads are scripted. */
@DataJpaTest
@Import(
	TaskRestacker::class, TaskPulls::class, FeatureStarter::class, TaskStarter::class, FeatureTasks::class,
	FeatureProgress::class, FeatureCreator::class, FeatureService::class, WorkflowReader::class, WorkflowCodec::class,
	CheckoutService::class, WorktreeService::class, WorktreeInspector::class, ProjectService::class, CheckoutLocks::class,
	GitRunner::class, CheckoutPaths::class, CheckoutTestConfig::class, FeatureTestConfig::class,
)
@EnableConfigurationProperties(FeatureProperties::class)
class TaskRestackTests {

	@Autowired
	lateinit var restacker: TaskRestacker

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
	fun `a moved lower branch is replayed under the task and pushed with a lease`() {
		commitAndPush(one, "a2.txt", "more\n")
		script(11 to pull(11, headSha = head("feat/001-x/01-api-a")))

		val view = restacker.restack(project.id!!, featureId, 2)

		assertEquals(head("feat/001-x/01-api-a"), git(two, "rev-parse", "HEAD~1"))
		assertEquals(git(two, "rev-parse", "HEAD"), head("feat/001-x/02-app-b"))
		assertEquals(head("feat/001-x/01-api-a"), view.task.baseHead)
		assertEquals("feat/001-x/01-api-a", view.task.baseBranch)
		assertFalse(view.needsRestack)
		then(pulls).should(never()).setBase(anyString(), anyString(), anyInt(), anyString())
	}

	@Test
	fun `after the lower tasks merged it moves onto the default branch and retargets the PR`() {
		val scratch = scratchClone(tmp, remote)
		git(scratch, "checkout", "main")
		Files.writeString(scratch.resolve("a.txt"), "one\n")
		git(scratch, "add", "-A")
		git(scratch, "commit", "-m", "Squash of 01")
		git(scratch, "push", "origin", "main")
		script(10 to pull(10, state = "merged"), 11 to pull(11, state = "merged"))

		val view = restacker.restack(project.id!!, featureId, 2)

		assertEquals("main", view.task.baseBranch)
		assertEquals(head("main"), git(two, "rev-parse", "HEAD~1"))
		assertEquals("two\n", Files.readString(two.resolve("b.txt")))
		then(pulls).should().setBase("acme", "factory", 12, "main")
	}

	@Test
	fun `a conflict leaves the branch, worktree and remote as they were`() {
		commitAndPush(two, "a.txt", "two's version\n")
		script(12 to pull(12, headSha = head("feat/001-x/02-app-b")))
		val before = head("feat/001-x/02-app-b")
		commitAndPush(one, "a.txt", "one's new version\n")
		script(11 to pull(11, headSha = head("feat/001-x/01-api-a")))

		val ex = assertThrows<RestackConflictException> { restacker.restack(project.id!!, featureId, 2) }

		assertEquals(listOf("a.txt"), ex.files)
		assertEquals(before, git(two, "rev-parse", "HEAD"))
		assertEquals(before, head("feat/001-x/02-app-b"))
		assertEquals("", git(two, "status", "--porcelain"))
		assertFalse(Path.of(git(two, "rev-parse", "--git-dir")).resolve("rebase-merge").exists())
		then(pulls).should(never()).setBase(anyString(), anyString(), anyInt(), anyString())
	}

	@Test
	fun `dirty, closed, current and missing tasks are refused`() {
		assertThrows<RestackNotNeededException> { restacker.restack(project.id!!, featureId, 2) }
		assertThrows<RestackNotNeededException> { restacker.restack(project.id!!, featureId, 0) }

		commitAndPush(one, "a2.txt", "more\n")
		script(11 to pull(11, headSha = head("feat/001-x/01-api-a")))
		Files.writeString(two.resolve("scratch.txt"), "uncommitted\n")
		assertThrows<TaskDirtyException> { restacker.restack(project.id!!, featureId, 2) }
		Files.delete(two.resolve("scratch.txt"))

		script(12 to pull(12, state = "closed"))
		assertThrows<TaskClosedException> { restacker.restack(project.id!!, featureId, 2) }
		script(12 to pull(12, headSha = head("feat/001-x/02-app-b")))

		FileSystemUtils.deleteRecursively(two)
		assertThrows<FeatureWorktreeMissingException> { restacker.restack(project.id!!, featureId, 2) }
	}

	@Test
	fun `a remote moved by someone else refuses the push and retargets nothing`() {
		commitAndPush(one, "a2.txt", "more\n")
		script(11 to pull(11, headSha = head("feat/001-x/01-api-a")))
		willAnswer {
			val other = scratchClone(tmp, remote)
			git(other, "checkout", "feat/001-x/02-app-b")
			Files.writeString(other.resolve("c.txt"), "someone else\n")
			git(other, "add", "-A")
			git(other, "commit", "-m", "Someone else")
			git(other, "push", "origin", "feat/001-x/02-app-b")
			it.callRealMethod()
		}.given(worktreeService).push(anyLong(), anyLong(), eq(true))

		assertThrows<PushRejectedException> { restacker.restack(project.id!!, featureId, 2) }

		then(pulls).should(never()).setBase(anyString(), anyString(), anyInt(), anyString())
	}

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
