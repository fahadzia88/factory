package com.toptier.custom.feature

import com.toptier.custom.checkout.CheckoutTestConfig
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.ROOT
import com.toptier.custom.checkout.CheckoutTestConfig.Companion.START
import com.toptier.custom.checkout.GitFixtures.fileUrl
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktreeRepository
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.CheckoutPaths
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeInspector
import com.toptier.custom.checkout.service.WorktreeService
import com.toptier.custom.feature.config.FeatureProperties
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.service.FeatureCreator
import com.toptier.custom.feature.service.FeatureService
import com.toptier.custom.feature.service.WorkflowCodec
import com.toptier.custom.feature.service.WorkflowReader
import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
import com.toptier.custom.project.service.ProjectService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.FileSystemUtils
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals

/** Two creates at once on one project; outside a test transaction so the threads see each other's rows. */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
	FeatureCreator::class, FeatureService::class, WorkflowReader::class, WorkflowCodec::class,
	CheckoutService::class, WorktreeService::class, WorktreeInspector::class, ProjectService::class, CheckoutLocks::class,
	GitRunner::class, CheckoutPaths::class, CheckoutTestConfig::class, FeatureTestConfig::class,
)
@EnableConfigurationProperties(FeatureProperties::class)
class FeatureConcurrencyTests {

	@Autowired
	lateinit var creator: FeatureCreator

	@Autowired
	lateinit var checkoutService: CheckoutService

	@Autowired
	lateinit var projectRepository: ProjectRepository

	@Autowired
	lateinit var features: FeatureRepository

	@Autowired
	lateinit var checkouts: RepositoryCheckoutRepository

	@Autowired
	lateinit var worktrees: RepositoryWorktreeRepository

	@MockitoBean
	lateinit var checker: GitHubRepositoryChecker

	@TempDir
	lateinit var tmp: Path

	@AfterEach
	fun clean() {
		features.deleteAll()
		worktrees.deleteAll()
		checkouts.deleteAll()
		projectRepository.deleteAll()
		ROOT.listDirectoryEntries().forEach { FileSystemUtils.deleteRecursively(it) }
	}

	@Test
	fun `two creates at once get different numbers and branches`() {
		val remote = FeatureFixtures.seededRemote(tmp)
		val project = projectRepository.saveAndFlush(Project().apply {
			name = "Concurrent"
			nameKey = "concurrent"
			repositoryUrl = fileUrl(remote)
			createdAt = START
			updatedAt = START
		})
		checkoutService.start(project.id!!)
		val ready = CountDownLatch(1)
		val pool = Executors.newFixedThreadPool(2)

		val results = (1..2).map {
			pool.submit<String> {
				ready.await()
				creator.create(project.id!!, "Same name").feature.branch
			}
		}
		ready.countDown()
		val branches = results.map { it.get(2, TimeUnit.MINUTES) }.sorted()
		pool.shutdown()

		assertEquals(listOf("feat/001-same-name/00-docs", "feat/002-same-name/00-docs"), branches)
		assertEquals(listOf(2, 1), features.findByProjectIdOrderByNumberDesc(project.id!!).map { it.number })
	}
}
