package com.toptier.custom.project.service

import com.toptier.custom.github.service.GitHubRepositoryChecker
import com.toptier.custom.github.service.RepositoryCheck
import com.toptier.custom.project.domain.Project
import com.toptier.custom.project.domain.ProjectRepository
import com.toptier.custom.project.domain.ProjectStatus
import com.toptier.custom.project.exception.GitHubUnavailableException
import com.toptier.custom.project.exception.ProjectArchivedException
import com.toptier.custom.project.exception.ProjectConflictException
import com.toptier.custom.project.exception.ProjectNotFoundException
import com.toptier.custom.project.exception.ProjectValidationException
import com.toptier.custom.project.exception.RepositoryNotFoundException
import jakarta.persistence.criteria.Predicate
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.Locale

/** A project as submitted for create or update, before validation; every field may be missing. */
data class ProjectInput(
	val name: String?,
	val repositoryUrl: String?,
	val description: String?,
	val status: String?,
)

/** Status counts and recent projects for the Dashboard; [counts] has every status, in lifecycle order. */
data class ProjectSummary(val counts: Map<ProjectStatus, Long>, val recent: List<Project>)

/** Creates, updates, archives, restores and lists projects, enforcing validation, uniqueness and the GitHub check. */
@Service
@Transactional
class ProjectService(
	private val repository: ProjectRepository,
	private val checker: GitHubRepositoryChecker,
	private val clock: Clock,
	private val events: ApplicationEventPublisher,
) {

	/** The project with this id, archived or not. */
	@Transactional(readOnly = true)
	fun get(id: Long): Project = repository.findById(id).orElseThrow { ProjectNotFoundException(id) }

	/** One page of projects matching [query]'s filters, sorted, with `id` breaking ties. */
	@Transactional(readOnly = true)
	fun list(query: ProjectListQuery): Page<Project> {
		val spec = Specification<Project> { root, _, cb ->
			val predicates = mutableListOf<Predicate>()
			if (!query.includeArchived) predicates += cb.isFalse(root.get<Boolean>("archived"))
			query.status?.let { predicates += cb.equal(root.get<ProjectStatus>("status"), it) }
			query.q?.let { predicates += cb.like(cb.lower(root.get<String>("name")), "%${escapeLike(it.lowercase(Locale.ROOT))}%", '\\') }
			cb.and(*predicates.toTypedArray())
		}
		val sort = Sort.by(query.direction, query.sort.property).and(Sort.by(query.direction, "id"))
		return repository.findAll(spec, PageRequest.of(query.page - 1, query.size, sort))
	}

	/** Non-archived projects only: a count for every status, and the five most recently updated. */
	@Transactional(readOnly = true)
	fun summary(): ProjectSummary {
		val found = repository.countByArchivedFalseGroupByStatus().associate { it.status to it.total }
		val counts = ProjectStatus.entries.associateWithTo(linkedMapOf()) { found[it] ?: 0L }
		return ProjectSummary(counts, repository.findTop5ByArchivedFalseOrderByUpdatedAtDescIdDesc())
	}

	/** Creates a project; status defaults to planning. The repository is checked with GitHub before saving. */
	fun create(input: ProjectInput): Project {
		val valid = validate(input, requireStatus = false)
		checkUnique(valid, excludeId = null)
		val now = clock.instant()
		val project = Project().apply {
			assign(valid)
			repositoryVerified = verify(valid.repository)
			createdAt = now
			updatedAt = now
		}
		return save(project)
	}

	/**
	 * Replaces a project's fields. GitHub is asked again only when the URL changed or the project is unverified,
	 * so other edits to a verified project make no outbound call.
	 */
	fun update(id: Long, input: ProjectInput): Project {
		val project = get(id)
		if (project.archived) throw ProjectArchivedException(id)
		val valid = validate(input, requireStatus = true)
		checkUnique(valid, excludeId = id)
		val recheck = valid.repositoryUrl != project.repositoryUrl || !project.repositoryVerified
		val verified = if (recheck) verify(valid.repository) else project.repositoryVerified
		project.assign(valid)
		project.repositoryVerified = verified
		project.updatedAt = clock.instant()
		return save(project)
	}

	/**
	 * Asks GitHub again about an unverified project's repository, changing nothing else: `updatedAt` stays, since
	 * no one edited the project. A verified project is returned as is.
	 */
	fun verify(id: Long): Project {
		val project = get(id)
		if (project.archived) throw ProjectArchivedException(id)
		if (project.repositoryVerified) return project
		val repository = checkNotNull(GitHubRepositoryUrl.parse(project.repositoryUrl)) { "Stored URL of project $id is invalid" }
		when (checker.check(repository.owner, repository.repo)) {
			RepositoryCheck.FOUND -> project.repositoryVerified = true
			RepositoryCheck.NOT_FOUND -> throw RepositoryNotFoundException(id)
			RepositoryCheck.UNAVAILABLE -> throw GitHubUnavailableException(id)
		}
		return save(project)
	}

	/**
	 * Hides a project from the default list and makes it read-only; its status is kept. Publishes a
	 * [ProjectArchivedEvent] every time, so a repeat archive also retries a cleanup that failed.
	 */
	fun archive(id: Long): Project {
		val project = setArchived(id, archived = true)
		events.publishEvent(ProjectArchivedEvent(id))
		return project
	}

	/** Makes an archived project editable and listed again, with the status it had. */
	fun restore(id: Long): Project = setArchived(id, archived = false)

	private fun setArchived(id: Long, archived: Boolean): Project {
		val project = get(id)
		if (project.archived == archived) return project
		project.archived = archived
		project.updatedAt = clock.instant()
		return save(project)
	}

	private fun Project.assign(valid: ValidInput) {
		name = valid.name
		nameKey = valid.nameKey
		repositoryUrl = valid.repositoryUrl
		description = valid.description
		status = valid.status
		statusOrder = valid.status.ordinal
	}

	/** True when GitHub confirms the repository; false when it couldn't be asked. Rejects a missing repository. */
	private fun verify(repository: GitHubRepositoryUrl): Boolean =
		when (checker.check(repository.owner, repository.repo)) {
			RepositoryCheck.FOUND -> true
			RepositoryCheck.UNAVAILABLE -> false
			RepositoryCheck.NOT_FOUND -> throw ProjectValidationException(mapOf("repositoryUrl" to "Repository not found on GitHub."))
		}

	/** Checked before calling GitHub, so a duplicate doesn't spend a request. */
	private fun checkUnique(valid: ValidInput, excludeId: Long?) {
		val nameTaken = excludeId?.let { repository.existsByNameKeyAndIdNot(valid.nameKey, it) }
			?: repository.existsByNameKey(valid.nameKey)
		val urlTaken = excludeId?.let { repository.existsByRepositoryUrlAndIdNot(valid.repositoryUrl, it) }
			?: repository.existsByRepositoryUrl(valid.repositoryUrl)
		val errors = buildMap {
			if (nameTaken) put("name", NAME_TAKEN)
			if (urlTaken) put("repositoryUrl", "Another project already uses this repository.")
		}
		if (errors.isNotEmpty()) throw ProjectConflictException(errors)
	}

	/** Flushes so a unique-constraint race between two saves surfaces here as a conflict. */
	private fun save(project: Project): Project =
		try {
			repository.saveAndFlush(project)
		} catch (_: DataIntegrityViolationException) {
			throw ProjectConflictException(mapOf("name" to NAME_TAKEN))
		}

	private fun validate(input: ProjectInput, requireStatus: Boolean): ValidInput {
		val errors = linkedMapOf<String, String>()
		val name = input.name?.trim().orEmpty()
		when {
			name.isEmpty() -> errors["name"] = "Enter a name."
			name.length > NAME_MAX -> errors["name"] = "Use $NAME_MAX characters or fewer."
		}
		val url = input.repositoryUrl?.trim().orEmpty()
		val repository = GitHubRepositoryUrl.parse(url)
		when {
			url.isEmpty() -> errors["repositoryUrl"] = "Enter the repository URL."
			repository == null -> errors["repositoryUrl"] = "Use a GitHub URL like https://github.com/owner/repo."
		}
		val description = input.description?.trim()?.takeIf { it.isNotEmpty() }
		if (description != null && description.length > DESCRIPTION_MAX) {
			errors["description"] = "Use 2,000 characters or fewer."
		}
		val status = when (val raw = input.status?.trim()?.takeIf { it.isNotEmpty() }) {
			null -> if (requireStatus) null.also { errors["status"] = CHOOSE_STATUS } else ProjectStatus.PLANNING
			else -> ProjectStatus.fromJson(raw) ?: null.also { errors["status"] = CHOOSE_STATUS }
		}
		if (errors.isNotEmpty()) throw ProjectValidationException(errors)
		return ValidInput(name, name.lowercase(Locale.ROOT), url, repository!!, description, status!!)
	}

	/** Makes `%`, `_` and the escape character itself match literally in a LIKE pattern. */
	private fun escapeLike(text: String): String =
		text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

	private data class ValidInput(
		val name: String,
		val nameKey: String,
		val repositoryUrl: String,
		val repository: GitHubRepositoryUrl,
		val description: String?,
		val status: ProjectStatus,
	)

	companion object {
		private const val NAME_MAX = 100
		private const val DESCRIPTION_MAX = 2000
		private const val NAME_TAKEN = "A project with this name already exists."
		private const val CHOOSE_STATUS = "Choose a status."
	}
}
