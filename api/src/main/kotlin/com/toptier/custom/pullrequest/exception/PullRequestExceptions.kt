package com.toptier.custom.pullrequest.exception

/** A pull request request that can't be answered. */
sealed class PullRequestException(message: String) : RuntimeException(message)

/** The repository exists, but has no pull request with this number. */
class PullRequestNotFoundException(val projectId: Long, val number: Int) :
	PullRequestException("Project $projectId has no pull request #$number")

/** GitHub can't find the project's repository, or the API's token can't see it. */
class PullRequestRepositoryNotFoundException(val projectId: Long) :
	PullRequestException("GitHub can't find the repository of project $projectId")

/** A page or pull request number below 1. */
class InvalidPullRequestParameterException(val name: String) : PullRequestException("Invalid value for $name.")
