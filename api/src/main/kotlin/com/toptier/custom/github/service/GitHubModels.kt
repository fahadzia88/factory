package com.toptier.custom.github.service

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/** One page of a GitHub list; GitHub sends no totals, only whether a next page exists. */
data class GitHubPage<T>(val items: List<T>, val hasNext: Boolean)

/** A pull request as GitHub returns it; the counts are only present on the single pull request endpoint. */
data class GitHubPull(
	val number: Int,
	val title: String,
	val state: String,
	val draft: Boolean = false,
	val user: GitHubUser?,
	val head: GitHubRef,
	val base: GitHubRef,
	val body: String?,
	@JsonProperty("created_at") val createdAt: Instant,
	@JsonProperty("updated_at") val updatedAt: Instant,
	@JsonProperty("merged_at") val mergedAt: Instant?,
	@JsonProperty("closed_at") val closedAt: Instant?,
	@JsonProperty("html_url") val htmlUrl: String,
	val commits: Int? = null,
	val additions: Int? = null,
	val deletions: Int? = null,
	@JsonProperty("changed_files") val changedFiles: Int? = null,
)

/** A GitHub account; GitHub sends `null` in its place for a deleted one. */
data class GitHubUser(
	val login: String,
	@JsonProperty("avatar_url") val avatarUrl: String,
)

/** A pull request's head or base: the branch, its `owner:branch` label, and the repository it lives in. */
data class GitHubRef(
	val ref: String,
	val label: String,
	val repo: GitHubRefRepo?,
	val sha: String? = null,
)

/** The repository a ref lives in; `null` when a fork was deleted. */
data class GitHubRefRepo(@JsonProperty("full_name") val fullName: String)

/** A file changed by a pull request; [patch] is absent for binary or very large files. */
data class GitHubFile(
	val filename: String,
	@JsonProperty("previous_filename") val previousFilename: String? = null,
	val status: String,
	val additions: Int,
	val deletions: Int,
	val patch: String? = null,
	@JsonProperty("blob_url") val blobUrl: String,
)

/** The check runs on a commit. */
data class GitHubCheckRuns(@JsonProperty("check_runs") val checkRuns: List<GitHubCheckRun>)

/** One check run; [conclusion] is null until it completes. */
data class GitHubCheckRun(
	val name: String,
	val status: String,
	val conclusion: String? = null,
	@JsonProperty("html_url") val htmlUrl: String? = null,
	val output: GitHubCheckOutput? = null,
)

/** A check run's result text. */
data class GitHubCheckOutput(val title: String? = null, val summary: String? = null)
