package com.toptier.custom.feature.service

import com.toptier.custom.checkout.domain.CheckoutState
import com.toptier.custom.checkout.domain.RepositoryCheckoutRepository
import com.toptier.custom.checkout.domain.RepositoryWorktree
import com.toptier.custom.checkout.exception.CheckoutNotReadyException
import com.toptier.custom.checkout.service.CheckoutLocks
import com.toptier.custom.checkout.service.CheckoutService
import com.toptier.custom.checkout.service.GitRunner
import com.toptier.custom.checkout.service.WorktreeService
import com.toptier.custom.feature.config.FeatureProperties
import com.toptier.custom.feature.domain.Feature
import com.toptier.custom.feature.domain.FeatureRepository
import com.toptier.custom.feature.exception.FeatureProjectArchivedException
import com.toptier.custom.project.service.ProjectService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Starts a feature: under the checkout's lock it fetches, checks the project's workflow and template,
 * numbers the feature, adds its docs worktree, commits `requirements.md` from the template, pushes and
 * saves the record. A failure after the worktree is added undoes it, so nothing half-made is left.
 */
@Service
class FeatureCreator(
	private val projects: ProjectService,
	private val checkouts: RepositoryCheckoutRepository,
	private val checkoutService: CheckoutService,
	private val worktreeService: WorktreeService,
	private val locks: CheckoutLocks,
	private val runner: GitRunner,
	private val reader: WorkflowReader,
	private val codec: WorkflowCodec,
	private val features: FeatureRepository,
	private val featureService: FeatureService,
	private val props: FeatureProperties,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/** Creates the feature named [rawName] on the project; throws the feature, checkout and git exceptions. */
	fun create(projectId: Long, rawName: String?): FeatureView {
		val project = projects.get(projectId)
		if (project.archived) throw FeatureProjectArchivedException()
		val checkout = checkouts.findByProjectId(projectId)?.takeIf { it.state == CheckoutState.READY }
			?: throw CheckoutNotReadyException()
		val name = FeatureNaming.name(rawName)
		val slug = FeatureNaming.slug(name)
		runner.requireToken()

		val saved = locks.withLock(checkout.id!!) {
			checkoutService.fetchUnderLock(checkout)
			val clone = Path.of(checkout.path)
			val workflow = reader.read(clone)
			val template = reader.requireTemplate(clone)
			val folders = runner.runOrThrow(
				clone,
				listOf("ls-tree", "-d", "--name-only", "origin/${checkout.defaultBranch}", "feature/"),
			).lines().filter { it.isNotBlank() }
			val number = FeatureNaming.nextNumber(features.maxNumber(projectId), folders)
			val code = FeatureNaming.code(number, slug)
			val branch = FeatureNaming.branch(code)
			val folder = FeatureNaming.folder(code)

			val worktree = worktreeService.add(projectId, branch, checkout.defaultBranch).worktree
			var pushed = false
			try {
				val dir = Path.of(worktree.path)
				Files.createDirectories(dir.resolve(folder))
				val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString()
				Files.writeString(
					dir.resolve("$folder/requirements.md"),
					template.replace("<NNN>-<slug>", code).replace("<Feature name>", name).replace("<date>", today),
				)
				runner.runOrThrow(dir, listOf("add", "--", folder))
				runner.runOrThrow(
					dir,
					listOf(
						"-c", "user.name=${props.commitName}", "-c", "user.email=${props.commitEmail}",
						"-c", "commit.gpgsign=false", "commit", "-m", "$code: start feature",
					),
				)
				worktreeService.push(projectId, worktree.id!!, force = false)
				pushed = true
				val now = clock.instant()
				features.saveAndFlush(Feature().apply {
					this.projectId = projectId
					this.number = number
					this.name = name
					this.slug = slug
					status = workflow.initialState
					this.workflow = codec.write(workflow)
					this.branch = branch
					this.folder = folder
					createdAt = now
					updatedAt = now
				})
			} catch (ex: Exception) {
				undo(projectId, clone, worktree, pushed)
				throw ex
			}
		}
		return featureService.view(saved)
	}

	/** Removes the worktree, its local branch and, once pushed, the branch on GitHub; each failure is logged, not thrown. */
	private fun undo(projectId: Long, clone: Path, worktree: RepositoryWorktree, pushed: Boolean) {
		val branch = worktree.branch
		attempt("remove the worktree of $branch") { worktreeService.remove(projectId, worktree.id!!, force = true) }
		attempt("delete the local branch $branch") {
			if (runner.refExists(clone, "refs/heads/$branch")) runner.runOrThrow(clone, listOf("branch", "-D", branch))
		}
		if (pushed) {
			attempt("delete $branch on GitHub") {
				runner.runOrThrow(clone, listOf("push", "origin", "--delete", branch), network = true)
			}
		}
	}

	private fun attempt(what: String, block: () -> Unit) {
		try {
			block()
		} catch (ex: Exception) {
			log.warn("Undoing a failed feature create: couldn't {}: {}", what, ex.message)
		}
	}
}
