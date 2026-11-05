package com.toptier.custom.project.domain

/** Where a project is in its life, in lifecycle order (declaration order is the sort order). */
enum class ProjectStatus(
	/** The name used in JSON and query parameters. */
	val json: String,
) {
	PLANNING("planning"),
	ACTIVE("active"),
	MAINTENANCE("maintenance"),
	RETIRED("retired"),
	;

	companion object {
		/** The status whose [json] name is [value], or null for anything else. */
		fun fromJson(value: String): ProjectStatus? = entries.firstOrNull { it.json == value }
	}
}
