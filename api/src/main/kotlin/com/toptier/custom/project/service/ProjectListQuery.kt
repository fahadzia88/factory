package com.toptier.custom.project.service

import com.toptier.custom.project.domain.ProjectStatus
import com.toptier.custom.project.exception.ProjectValidationException
import org.springframework.data.domain.Sort

/** A column the project list can be sorted by, with the entity property it sorts on. */
enum class ProjectSort(val param: String, val property: String, val defaultDirection: Sort.Direction) {
	UPDATED_AT("updatedAt", "updatedAt", Sort.Direction.DESC),

	/** Sorts on the lowercased name, so case doesn't affect the order. */
	NAME("name", "nameKey", Sort.Direction.ASC),

	/** Sorts in lifecycle order (planning → retired), not alphabetically. */
	STATUS("status", "statusOrder", Sort.Direction.ASC),
}

/** A validated `GET /api/projects` query: filters, sort and a 1-based page. */
data class ProjectListQuery(
	val q: String?,
	val status: ProjectStatus?,
	val includeArchived: Boolean,
	val sort: ProjectSort,
	val direction: Sort.Direction,
	val page: Int,
	val size: Int,
) {
	companion object {
		const val DEFAULT_SIZE = 20
		const val MAX_SIZE = 100

		/** Validates raw query parameters; every bad one is reported, keyed by its parameter name. */
		fun of(
			q: String? = null,
			status: String? = null,
			includeArchived: Boolean? = null,
			sort: String? = null,
			direction: String? = null,
			page: Int? = null,
			size: Int? = null,
		): ProjectListQuery {
			val errors = linkedMapOf<String, String>()
			val parsedStatus = status?.let { ProjectStatus.fromJson(it) ?: null.also { errors["status"] = UNKNOWN } }
			val parsedSort = sort?.let { s -> ProjectSort.entries.firstOrNull { it.param == s } ?: null.also { errors["sort"] = UNKNOWN } }
				?: ProjectSort.UPDATED_AT
			val parsedDirection = when (direction) {
				null -> parsedSort.defaultDirection
				"asc" -> Sort.Direction.ASC
				"desc" -> Sort.Direction.DESC
				else -> null.also { errors["direction"] = UNKNOWN }
			}
			val parsedPage = page ?: 1
			if (parsedPage < 1) errors["page"] = "Use a page of 1 or more."
			val parsedSize = size ?: DEFAULT_SIZE
			if (parsedSize !in 1..MAX_SIZE) errors["size"] = "Use a size from 1 to $MAX_SIZE."
			if (errors.isNotEmpty()) throw ProjectValidationException(errors)
			return ProjectListQuery(
				q = q?.trim()?.takeIf { it.isNotEmpty() },
				status = parsedStatus,
				includeArchived = includeArchived ?: false,
				sort = parsedSort,
				direction = parsedDirection!!,
				page = parsedPage,
				size = parsedSize,
			)
		}

		private const val UNKNOWN = "Unknown value."
	}
}
