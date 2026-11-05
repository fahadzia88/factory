package com.toptier.custom.project.exception

/** A project request that can't be carried out. */
sealed class ProjectException(message: String) : RuntimeException(message)

/** The input is invalid; [errors] maps a field (`name`, `repositoryUrl`, …) to a message for the user. */
class ProjectValidationException(val errors: Map<String, String>) :
	ProjectException("Invalid project: ${errors.keys.joinToString()}")

/** Another project already has this name or repository; [errors] says which field clashes. */
class ProjectConflictException(val errors: Map<String, String>) :
	ProjectException("Project conflicts on: ${errors.keys.joinToString()}")

/** The project is archived, so it can't be edited until it is restored. */
class ProjectArchivedException(val id: Long) :
	ProjectException("Archived projects can't be edited. Restore it first.")

/** No project has this id. */
class ProjectNotFoundException(val id: Long) : ProjectException("Project $id not found")
